package it.locale.chatmulti;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.text.Html;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import java.io.InputStream;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends Activity implements ChatEngine.EventListener {
    private static final int REQ_FILE = 101;
    private ChatEngine rete;
    private boolean chatAttiva = false;
    private String mioIpCorrente = "127.0.0.1";

    private ScrollView scrollChat;
    private TextView tvChat, tvProg, tvMioIp;
    private Spinner spDestinatario;
    private EditText etMsg, etNome, etCodice, etPorta;
    private AutoCompleteTextView actvIpDest;
    private Button btnInvia, btnFile, btnConnetti, btnInizia, btnDisconnetti;
    private ProgressBar pbFile;

    private ArrayAdapter<String> adapterDest;
    private ArrayAdapter<String> adapterIp;
    private List<String> listaIp = new ArrayList<String>();
    private Handler handler = new Handler();

    private Runnable timerRiconnetti = new Runnable() {
        @Override
        public void run() {
            if (chatAttiva) {
                rete.inviaPingATutti();
                int porta = getPorta();
                for (String ip : listaIp) {
                    if (ip != null && !ip.equals(mioIpCorrente)) {
                        rete.connettiA(ip, porta);
                    }
                }
            }
            handler.postDelayed(this, 6000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        rete = new ChatEngine(this);
        collegaUI();
        caricaImpostazioni();

        mioIpCorrente = getIndirizzoLocale();
        tvMioIp.setText("Mio IP: " + mioIpCorrente);
        log("Pronto. Clicca su 'Attiva Chat' per iniziare.", "#808080");

        handler.postDelayed(timerRiconnetti, 6000);
    }

    private void collegaUI() {
        scrollChat = (ScrollView) findViewById(R.id.scrollChat);
        tvChat = (TextView) findViewById(R.id.tvChat);
        tvProg = (TextView) findViewById(R.id.tvProg);
        tvMioIp = (TextView) findViewById(R.id.tvMioIp);
        spDestinatario = (Spinner) findViewById(R.id.spDestinatario);
        etMsg = (EditText) findViewById(R.id.etMsg);
        etNome = (EditText) findViewById(R.id.etNome);
        etCodice = (EditText) findViewById(R.id.etCodice);
        etPorta = (EditText) findViewById(R.id.etPorta);
        actvIpDest = (AutoCompleteTextView) findViewById(R.id.actvIpDest);
        btnInvia = (Button) findViewById(R.id.btnInvia);
        btnFile = (Button) findViewById(R.id.btnFile);
        btnConnetti = (Button) findViewById(R.id.btnConnetti);
        btnInizia = (Button) findViewById(R.id.btnInizia);
        btnDisconnetti = (Button) findViewById(R.id.btnDisconnetti);
        pbFile = (ProgressBar) findViewById(R.id.pbFile);

        adapterDest = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item);
        adapterDest.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        adapterDest.add("Tutti i PC");
        spDestinatario.setAdapter(adapterDest);

        adapterIp = new ArrayAdapter<String>(this, android.R.layout.simple_dropdown_item_1line, listaIp);
        actvIpDest.setAdapter(adapterIp);
        actvIpDest.setThreshold(1);

        btnInizia.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                aggiornaStatoUI(true);
                salvaImpostazioni();
                int porta = getPorta();
                rete.avviaServer(porta, etNome.getText().toString(), etCodice.getText().toString());
                if (!listaIp.isEmpty()) {
                    log("Connessione automatica verso i PC salvati...", "#FF8C00");
                    for (String ip : listaIp) {
                        if (!ip.equals(mioIpCorrente)) rete.connettiA(ip, porta);
                    }
                }
            }
        });

        btnDisconnetti.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                rete.chiudiTutto();
                aggiornaStatoUI(false);
                pbFile.setProgress(0);
                tvProg.setText("");
                log("Disconnesso dalla chat.", "#FF4500");
            }
        });

        btnConnetti.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String ip = actvIpDest.getText().toString().trim();
                if (ip.length() == 0) return;
                if (ip.equals(mioIpCorrente)) {
                    log("Non puoi collegarti al tuo stesso IP.", "#B22222");
                    return;
                }
                if (!chatAttiva) {
                    aggiornaStatoUI(true);
                    rete.avviaServer(getPorta(), etNome.getText().toString(), etCodice.getText().toString());
                }
                if (!listaIp.contains(ip)) {
                    listaIp.add(0, ip);
                    adapterIp.notifyDataSetChanged();
                }
                salvaImpostazioni();
                log("Tentativo manuale verso " + ip + "...", "#808080");
                rete.connettiA(ip, getPorta());
            }
        });

        btnInvia.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { inviaMessaggio(); }
        });

        etMsg.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEND || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    inviaMessaggio();
                    return true;
                }
                return false;
            }
        });

        btnFile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!chatAttiva) {
                    log("Attiva prima la chat.", "#B22222");
                    return;
                }
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("*/*");
                startActivityForResult(Intent.createChooser(intent, "Seleziona file"), REQ_FILE);
            }
        });
    }

    private void inviaMessaggio() {
        String t = etMsg.getText().toString().trim();
        if (t.length() == 0) return;
        if (!chatAttiva) {
            log("Attiva prima la chat.", "#B22222");
            return;
        }
        String targetIp = getIpDestinatarioSelezionato();
        rete.inviaTesto(t, targetIp);
        if (targetIp.length() > 0) {
            log("Tu [Privato a " + spDestinatario.getSelectedItem() + "]: " + t, "#800080");
        } else {
            log("Tu: " + t, "#006400");
        }
        etMsg.setText("");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            try {
                Uri uri = data.getData();
                String nome = "file_inviato";
                long size = 0;
                Cursor c = getContentResolver().query(uri, null, null, null, null);
                if (c != null && c.moveToFirst()) {
                    int nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int sizeIdx = c.getColumnIndex(OpenableColumns.SIZE);
                    if (nameIdx >= 0) nome = c.getString(nameIdx);
                    if (sizeIdx >= 0) size = c.getLong(sizeIdx);
                    c.close();
                }
                InputStream is = getContentResolver().openInputStream(uri);
                rete.inviaFile(is, nome, size, getIpDestinatarioSelezionato());
            } catch (Exception e) {
                log("Errore apertura file: " + e.getMessage(), "#B22222");
            }
        }
    }

    @Override
    public void onEvent(final String tipo, final String... args) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if ("stato".equals(tipo)) log(args[0], "#808080");
                else if ("errore".equals(tipo)) log("Info: " + args[0], "#B22222");
                else if ("connesso".equals(tipo)) {
                    String voce = args[0] + " (" + args[1] + ")";
                    if (adapterDest.getPosition(voce) < 0) adapterDest.add(voce);
                    log("--> ENTRATO IN CHAT: " + voce, "#008B8B");
                } else if ("disconnesso".equals(tipo)) {
                    String voce = args[0] + " (" + args[1] + ")";
                    adapterDest.remove(voce);
                    log("<-- USCITO DALLA CHAT: " + args[0], "#FF4500");
                } else if ("testo".equals(tipo)) {
                    log(args[0] + ": " + args[1], "#4169E1");
                } else if ("privato".equals(tipo)) {
                    log("[Privato da " + args[0] + "]: " + args[1], "#800080");
                } else if ("invio".equals(tipo)) {
                    log("Invio file: " + args[0], "#808080");
                } else if ("ricezione".equals(tipo)) {
                    log("Ricezione file: " + args[0], "#808080");
                } else if ("inviato".equals(tipo)) {
                    log("File inviato: " + args[0], "#006400");
                    pbFile.setProgress(0); tvProg.setText("");
                } else if ("ricevuto".equals(tipo)) {
                    log("File RICEVUTO da " + args[0] + ": " + args[1] + " in Download/ScambioFile", "#4169E1");
                    pbFile.setProgress(0); tvProg.setText("");
                } else if ("progresso".equals(tipo)) {
                    long cur = Long.parseLong(args[2]);
                    long tot = Long.parseLong(args[3]);
                    int perc = tot > 0 ? (int) Math.min(100, (100 * cur) / tot) : 0;
                    pbFile.setProgress(perc);
                    tvProg.setText(perc + "%");
                }
            }
        });
    }

    private void log(String testo, String hexColor) {
        String ora = new SimpleDateFormat("HH:mm:ss", Locale.ITALY).format(new Date());
        String html = "<font color='" + hexColor + "'>[" + ora + "] " + testo + "</font><br/>";
        tvChat.append(Html.fromHtml(html));
        scrollChat.post(new Runnable() {
            @Override
            public void run() { scrollChat.fullScroll(View.FOCUS_DOWN); }
        });
    }

    private void aggiornaStatoUI(boolean attiva) {
        chatAttiva = attiva;
        etNome.setEnabled(!attiva);
        etCodice.setEnabled(!attiva);
        etPorta.setEnabled(!attiva);
        btnInizia.setEnabled(!attiva);
        btnDisconnetti.setEnabled(attiva);
        if (!attiva) {
            adapterDest.clear();
            adapterDest.add("Tutti i PC");
        }
    }

    private String getIpDestinatarioSelezionato() {
        Object sel = spDestinatario.getSelectedItem();
        if (sel == null) return "";
        String s = sel.toString();
        if (!"Tutti i PC".equals(s) && s.endsWith(")")) {
            int idx = s.lastIndexOf('(');
            if (idx >= 0) return s.substring(idx + 1, s.length() - 1);
        }
        return "";
    }

    private int getPorta() {
        try {
            int p = Integer.parseInt(etPorta.getText().toString().trim());
            return (p >= 1024 && p <= 65535) ? p : 5050;
        } catch (Exception e) { return 5050; }
    }

    private void salvaImpostazioni() {
        SharedPreferences sp = getSharedPreferences("ChatMultiPrefs", MODE_PRIVATE);
        SharedPreferences.Editor ed = sp.edit();
        ed.putString("Nome", etNome.getText().toString());
        ed.putString("Codice", etCodice.getText().toString());
        ed.putInt("Porta", getPorta());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < listaIp.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(listaIp.get(i));
        }
        ed.putString("IpSalvati", sb.toString());
        ed.commit();
    }

    private void caricaImpostazioni() {
        SharedPreferences sp = getSharedPreferences("ChatMultiPrefs", MODE_PRIVATE);
        etNome.setText(sp.getString("Nome", Build.MODEL));
        etCodice.setText(sp.getString("Codice", "1234"));
        etPorta.setText(String.valueOf(sp.getInt("Porta", 5050)));
        String ips = sp.getString("IpSalvati", "");
        listaIp.clear();
        if (ips.length() > 0) {
            for (String ip : ips.split(",")) {
                if (ip.length() > 0) listaIp.add(ip);
            }
            if (!listaIp.isEmpty()) actvIpDest.setText(listaIp.get(0));
        }
    }

    private String getIndirizzoLocale() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if (!intf.isUp() || intf.isLoopback()) continue;
                for (InetAddress addr : Collections.list(intf.getInetAddresses())) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        String ip = addr.getHostAddress();
                        if (!ip.startsWith("169.254.")) return ip;
                    }
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(timerRiconnetti);
        salvaImpostazioni();
        rete.chiudiTutto();
        super.onDestroy();
    }
}
