package it.locale.chatmulti;

import android.os.Environment;
import java.io.*;
import java.net.*;
import java.nio.charset.Charset;
import java.util.*;

public class ChatEngine {
    private static final byte T_HELLO  = 1;
    private static final byte T_TESTO  = 2;
    private static final byte T_FSTART = 3;
    private static final byte T_FDATA  = 4;
    private static final byte T_FEND   = 5;
    private static final byte T_FABORT = 6;
    private static final byte T_BYE    = 7;
    private static final byte T_PRIV   = 8;
    private static final byte T_PING   = 9;

    private static final Charset UTF8 = Charset.forName("UTF-8");

    public interface EventListener {
        void onEvent(String tipo, String... args);
    }

    public static class Peer {
        public Socket socket;
        public InputStream in;
        public OutputStream out;
        public String nome = "Sconosciuto";
        public String ip = "";
        public boolean inviandoFile = false;
        public final Object writeLock = new Object();
    }

    private final List<Peer> peers = new ArrayList<Peer>();
    private final Set<String> inConnessione = new HashSet<String>();
    private final Object lockPeers = new Object();

    private ServerSocket serverSocket;
    private volatile boolean inAscolto = false;
    private String mioNome = "";
    private String mioCodice = "";
    private File cartellaRicevuti;
    private EventListener listener;

    public ChatEngine(EventListener listener) {
        this.listener = listener;
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        this.cartellaRicevuti = new File(downloadDir, "ScambioFile");
    }

    private void evento(String tipo, String... args) {
        if (listener != null) listener.onEvent(tipo, args);
    }

    public void avviaServer(final int porta, String nome, String codice) {
        chiudiTutto();
        this.mioNome = nome;
        this.mioCodice = codice;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    serverSocket = new ServerSocket();
                    serverSocket.setReuseAddress(true);
                    serverSocket.bind(new InetSocketAddress(porta));
                    inAscolto = true;
                    evento("stato", "In ascolto sulla porta " + porta + "... Pronto.");

                    while (inAscolto) {
                        try {
                            final Socket client = serverSocket.accept();
                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    gestisciNuovoClient(client);
                                }
                            }).start();
                        } catch (Exception e) {
                            break;
                        }
                    }
                } catch (Exception ex) {
                    evento("errore", "Errore server porta " + porta + ": " + ex.getMessage());
                }
            }
        }).start();
    }

    private boolean registraPeerSeUnico(Peer nuovo) {
        synchronized (lockPeers) {
            if (!inAscolto) return false;
            for (Peer esistente : peers) {
                if (esistente.ip.equals(nuovo.ip)) return false;
            }
            peers.add(nuovo);
            return true;
        }
    }

    private void gestisciNuovoClient(Socket c) {
        try {
            if (!inAscolto) { c.close(); return; }
            c.setTcpNoDelay(true);
            c.setSoLinger(true, 0);
            String remoteIp = c.getInetAddress().getHostAddress();

            synchronized (lockPeers) {
                for (Peer esistente : peers) {
                    if (esistente.ip.equals(remoteIp)) { c.close(); return; }
                }
            }

            c.setSoTimeout(5000);
            InputStream in = c.getInputStream();
            OutputStream out = c.getOutputStream();

            String[] hello = leggiHello(in);
            if (hello == null || !hello[1].equals(mioCodice) || !inAscolto) {
                c.close();
                return;
            }

            byte[] resp = (mioNome + "\n" + mioCodice).getBytes(UTF8);
            scriviFrame(out, T_HELLO, resp, 0, resp.length);
            c.setSoTimeout(0);

            Peer p = new Peer();
            p.socket = c;
            p.in = in;
            p.out = out;
            p.nome = hello[0];
            p.ip = remoteIp;

            if (!registraPeerSeUnico(p)) { c.close(); return; }

            evento("connesso", p.nome, p.ip);
            cicloRicezione(p);
        } catch (Exception e) {
            try { c.close(); } catch (Exception ignored) {}
        }
    }

    public void connettiA(final String host, final int porta) {
        if (!inAscolto || host == null || host.length() == 0) return;

        synchronized (lockPeers) {
            for (Peer p : peers) {
                if (p.ip.equals(host)) return;
            }
            if (inConnessione.contains(host)) return;
            inConnessione.add(host);
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                Socket c = new Socket();
                try {
                    c.setSoLinger(true, 0);
                    c.connect(new InetSocketAddress(host, porta), 2000);
                    if (!inAscolto) { c.close(); return; }

                    c.setTcpNoDelay(true);
                    c.setSoTimeout(5000);
                    InputStream in = c.getInputStream();
                    OutputStream out = c.getOutputStream();

                    byte[] miaHello = (mioNome + "\n" + mioCodice).getBytes(UTF8);
                    scriviFrame(out, T_HELLO, miaHello, 0, miaHello.length);

                    String[] hello = leggiHello(in);
                    if (hello == null || !hello[1].equals(mioCodice) || !inAscolto) {
                        c.close();
                        return;
                    }
                    c.setSoTimeout(0);

                    Peer p = new Peer();
                    p.socket = c;
                    p.in = in;
                    p.out = out;
                    p.nome = hello[0];
                    p.ip = host;

                    if (!registraPeerSeUnico(p)) { c.close(); return; }

                    evento("connesso", p.nome, p.ip);
                    cicloRicezione(p);
                } catch (Exception e) {
                    try { c.close(); } catch (Exception ignored) {}
                } finally {
                    synchronized (lockPeers) { inConnessione.remove(host); }
                }
            }
        }).start();
    }

    public void inviaPingATutti() {
        if (!inAscolto) return;
        List<Peer> lista;
        synchronized (lockPeers) { lista = new ArrayList<Peer>(peers); }
        for (Peer p : lista) {
            try {
                synchronized (p.writeLock) { scriviFrame(p.out, T_PING, null, 0, 0); }
            } catch (Exception e) {
                try { p.socket.close(); } catch (Exception ignored) {}
            }
        }
    }

    public void inviaTesto(String testo, String targetIp) {
        byte[] b = testo.getBytes(UTF8);
        byte tipoMsg = (targetIp == null || targetIp.length() == 0) ? T_TESTO : T_PRIV;
        List<Peer> lista;
        synchronized (lockPeers) { lista = new ArrayList<Peer>(peers); }

        for (Peer p : lista) {
            if (targetIp == null || targetIp.length() == 0 || p.ip.equals(targetIp)) {
                try {
                    synchronized (p.writeLock) { scriviFrame(p.out, tipoMsg, b, 0, b.length); }
                } catch (Exception e) {
                    try { p.socket.close(); } catch (Exception ignored) {}
                }
            }
        }
    }

    public void inviaFile(final InputStream inputStream, final String nomeFile, final long dimensione, String targetIp) {
        final List<Peer> destinatari = new ArrayList<Peer>();
        synchronized (lockPeers) {
            for (Peer p : peers) {
                if (targetIp == null || targetIp.length() == 0 || p.ip.equals(targetIp)) {
                    destinatari.add(p);
                }
            }
        }
        if (destinatari.isEmpty()) {
            evento("errore", "Nessun destinatario connesso.");
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] header = (nomeFile + "\n" + dimensione).getBytes(UTF8);

                    for (Peer p : destinatari) {
                        try {
                            synchronized (p.writeLock) { scriviFrame(p.out, T_FSTART, header, 0, header.length); }
                            p.inviandoFile = true;
                        } catch (Exception e) { p.inviandoFile = false; }
                    }

                    evento("invio", nomeFile, String.valueOf(dimensione));
                    byte[] buf = new byte[60000];
                    long inviati = 0;
                    int n;
                    long ultimo = 0;

                    while ((n = inputStream.read(buf)) > 0) {
                        inviati += n;
                        for (Peer p : destinatari) {
                            if (p.inviandoFile) {
                                try {
                                    synchronized (p.writeLock) { scriviFrame(p.out, T_FDATA, buf, 0, n); }
                                } catch (Exception e) { p.inviandoFile = false; }
                            }
                        }
                        long now = System.currentTimeMillis();
                        if (now - ultimo > 200) {
                            ultimo = now;
                            evento("progresso", "I", nomeFile, String.valueOf(inviati), String.valueOf(dimensione));
                        }
                    }

                    for (Peer p : destinatari) {
                        if (p.inviandoFile) {
                            try {
                                synchronized (p.writeLock) { scriviFrame(p.out, T_FEND, null, 0, 0); }
                            } catch (Exception ignored) {}
                            p.inviandoFile = false;
                        }
                    }
                    evento("inviato", nomeFile, String.valueOf(dimensione));
                } catch (Exception ex) {
                    evento("errore", "Errore invio file: " + ex.getMessage());
                } finally {
                    if (inputStream != null) try { inputStream.close(); } catch (Exception ignored) {}
                }
            }
        }).start();
    }

    private void cicloRicezione(Peer p) {
        FileOutputStream fos = null;
        File fileCorrente = null;
        String nomeFile = null;
        long atteso = 0, ricevuti = 0, ultimo = 0;

        try {
            byte[] tipoOut = new byte[1];
            while (inAscolto) {
                byte[] dati = leggiFrame(p.in, tipoOut, 70000);
                if (dati == null) break;
                byte tipo = tipoOut[0];

                if (tipo == T_PING) continue;
                if (tipo == T_BYE) break;

                if (tipo == T_TESTO) {
                    evento("testo", p.nome, new String(dati, UTF8));
                } else if (tipo == T_PRIV) {
                    evento("privato", p.nome, new String(dati, UTF8));
                } else if (tipo == T_FSTART) {
                    scartaFile(fos, fileCorrente);
                    String[] parti = new String(dati, UTF8).split("\n", 2);
                    nomeFile = parti[0].replaceAll("[\\\\/:*?\"<>|]", "_");
                    atteso = parti.length > 1 ? Long.parseLong(parti[1]) : 0;
                    ricevuti = 0;
                    if (!cartellaRicevuti.exists()) cartellaRicevuti.mkdirs();
                    fileCorrente = percorsoLibero(nomeFile);
                    fos = new FileOutputStream(fileCorrente);
                    evento("ricezione", nomeFile, String.valueOf(atteso));
                } else if (tipo == T_FDATA && fos != null) {
                    fos.write(dati);
                    ricevuti += dati.length;
                    long now = System.currentTimeMillis();
                    if (now - ultimo > 200) {
                        ultimo = now;
                        evento("progresso", "R", nomeFile, String.valueOf(ricevuti), String.valueOf(atteso));
                    }
                } else if (tipo == T_FEND && fos != null) {
                    fos.close(); fos = null;
                    evento("ricevuto", p.nome, nomeFile, fileCorrente.getAbsolutePath(), String.valueOf(ricevuti));
                    fileCorrente = null;
                } else if (tipo == T_FABORT) {
                    scartaFile(fos, fileCorrente); fos = null; fileCorrente = null;
                }
            }
        } catch (Exception ignored) {}

        scartaFile(fos, fileCorrente);
        boolean rimosso;
        synchronized (lockPeers) { rimosso = peers.remove(p); }
        try { p.socket.close(); } catch (Exception ignored) {}
        if (rimosso) evento("disconnesso", p.nome, p.ip);
    }

    private File percorsoLibero(String nome) {
        File f = new File(cartellaRicevuti, nome);
        if (!f.exists()) return f;
        int dot = nome.lastIndexOf('.');
        String stem = dot > 0 ? nome.substring(0, dot) : nome;
        String ext = dot > 0 ? nome.substring(dot) : "";
        for (int i = 1; i < 10000; i++) {
            f = new File(cartellaRicevuti, stem + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
        return new File(cartellaRicevuti, System.currentTimeMillis() + ext);
    }

    public void chiudiTutto() {
        inAscolto = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        List<Peer> daChiudere;
        synchronized (lockPeers) {
            daChiudere = new ArrayList<Peer>(peers);
            peers.clear();
            inConnessione.clear();
        }
        for (Peer p : daChiudere) {
            try {
                synchronized (p.writeLock) { scriviFrame(p.out, T_BYE, null, 0, 0); }
                p.out.flush();
            } catch (Exception ignored) {}
            try { p.socket.close(); } catch (Exception ignored) {}
        }
    }

    private void scartaFile(FileOutputStream fos, File f) {
        if (fos != null) try { fos.close(); } catch (Exception ignored) {}
        if (f != null && f.exists()) f.delete();
    }

    private static void scriviFrame(OutputStream out, byte tipo, byte[] dati, int offset, int count) throws IOException {
        byte[] head = new byte[5];
        head[0] = tipo;
        head[1] = (byte) (count & 0xFF);
        head[2] = (byte) ((count >> 8) & 0xFF);
        head[3] = (byte) ((count >> 16) & 0xFF);
        head[4] = (byte) ((count >> 24) & 0xFF);
        out.write(head);
        if (count > 0 && dati != null) out.write(dati, offset, count);
        out.flush();
    }

    private static byte[] leggiFrame(InputStream in, byte[] tipoOut, int max) throws IOException {
        byte[] head = new byte[5];
        if (!leggiEsatto(in, head, 5)) return null;
        tipoOut[0] = head[0];
        int len = (head[1] & 0xFF) | ((head[2] & 0xFF) << 8) | ((head[3] & 0xFF) << 16) | ((head[4] & 0xFF) << 24);
        if (len < 0 || len > max) return null;
        byte[] dati = new byte[len];
        if (len > 0 && !leggiEsatto(in, dati, len)) return null;
        return dati;
    }

    private static boolean leggiEsatto(InputStream in, byte[] buf, int n) throws IOException {
        int tot = 0;
        while (tot < n) {
            int r = in.read(buf, tot, n - tot);
            if (r <= 0) return false;
            tot += r;
        }
        return true;
    }

    private static String[] leggiHello(InputStream in) throws IOException {
        byte[] tipoOut = new byte[1];
        byte[] dati = leggiFrame(in, tipoOut, 1024);
        if (dati == null || tipoOut[0] != T_HELLO) return null;
        String[] p = new String(dati, UTF8).split("\n", 2);
        return new String[]{ p.length > 0 ? p[0] : "Sconosciuto", p.length > 1 ? p[1] : "" };
    }
}
