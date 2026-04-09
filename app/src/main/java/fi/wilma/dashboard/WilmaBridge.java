package fi.wilma.dashboard;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class WilmaBridge {

    private static final String PREFS   = "WilmaDashboard";
    private static final String KEY_URL  = "tenantUrl";
    private static final String KEY_NAME = "tenantName";
    private static final String KEY_USER = "username";
    private static final String KEY_PASS = "password";
    private static final String KEY_LANG = "language";
    private static final String KEY_CACHE= "cachedData";

    private static final String USER_AGENT =
        "Mozilla/5.0 (Linux; Android 4.2.2; GT-P3110) " +
        "AppleWebKit/534.30 (KHTML, like Gecko) " +
        "Version/4.0 Mobile Safari/534.30";

    private static final String[] NAV_KEYWORDS = {
        "messages","viestit","schedule","lukujärjestys",
        "gradebook","assessments","exams","attendance",
        "poissaolot","printouts","news"
    };

    // ── Shared cookie jar ────────────────────────────────────────────────────
    private static final CookieManager cookieManager =
        new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    static {
        java.net.CookieHandler.setDefault(cookieManager);
    }

    // ── Data store — JS retrieves these synchronously (avoids loadUrl encoding issues) ──
    private volatile String latestDataJson    = "";
    private volatile String latestSetupResult = "";
    private volatile String latestError       = "";

    private final WeakReference<WebView>  webViewRef;
    private final WeakReference<Activity> activityRef;
    private final SharedPreferences       prefs;

    public WilmaBridge(WebView webView, Activity activity) {
        this.webViewRef  = new WeakReference<>(webView);
        this.activityRef = new WeakReference<>(activity);
        this.prefs       = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        initSSL();
    }

    // ── SSL setup: trust all certs + enable TLS 1.2 on old Android ──────────
    //   Android 4.2 has an outdated CA store and won't negotiate TLS 1.2 by
    //   default. Trust-all lets us connect even with cert chain issues.
    private void initSSL() {
        try {
            TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    @Override public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                    @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
                    @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
                }
            };
            SSLContext sc = SSLContext.getInstance("TLSv1.2");
            sc.init(null, trustAll, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(
                new Tls12SocketFactory(sc.getSocketFactory()));
            HttpsURLConnection.setDefaultHostnameVerifier(
                new HostnameVerifier() {
                    @Override public boolean verify(String host, SSLSession s) { return true; }
                });
        } catch (Exception ignored) {}
    }

    // ── Public interface ─────────────────────────────────────────────────────

    @JavascriptInterface
    public boolean hasCredentials() {
        return !prefs.getString(KEY_URL, "").isEmpty()
            && !prefs.getString(KEY_USER, "").isEmpty()
            && !prefs.getString(KEY_PASS, "").isEmpty();
    }

    /**
     * Attempt login with supplied credentials.
     * Result stored in latestSetupResult.
     * Signals JS via window.onSetupDone() — NO data in URL.
     */
    @JavascriptInterface
    public void setup(final String tenantUrl, final String tenantName,
                      final String username,  final String password) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String cleanUrl = tenantUrl.trim().replaceAll("/+$", "");
                    resetCookies();
                    doLogin(cleanUrl, username, password);
                    prefs.edit()
                        .putString(KEY_URL,  cleanUrl)
                        .putString(KEY_NAME, tenantName.trim())
                        .putString(KEY_USER, username.trim())
                        .putString(KEY_PASS, password)
                        .apply();
                    latestSetupResult = "{\"ok\":true}";
                } catch (Exception e) {
                    String msg = e.getMessage() != null ? e.getMessage() : "Login failed";
                    latestSetupResult = "{\"error\":\"" + escJson(msg) + "\"}";
                }
                signal("window.onSetupDone()");
            }
        }).start();
    }

    /** JS retrieves this after onSetupDone fires. */
    @JavascriptInterface
    public String getLatestSetupResult() {
        return latestSetupResult;
    }

    /** Return cached data from last successful fetch (may be empty). */
    @JavascriptInterface
    public String getCachedData() {
        return prefs.getString(KEY_CACHE, "");
    }

    /**
     * Fetch fresh data from Wilma.
     * Result stored in latestDataJson.
     * Signals JS via window.onDataReady() or window.onDataError() — NO data in URL.
     */
    @JavascriptInterface
    public void fetchData() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String json = fetchAllStudents();
                    latestDataJson = json;
                    latestError    = "";
                    prefs.edit().putString(KEY_CACHE, json).apply();
                    signal("window.onDataReady()");
                } catch (Exception e) {
                    latestError = e.getMessage() != null ? e.getMessage() : "Fetch failed";
                    signal("window.onDataError()");
                }
            }
        }).start();
    }

    /** JS retrieves this after onDataReady fires. */
    @JavascriptInterface
    public String getLatestDataJson() {
        return latestDataJson;
    }

    /** JS retrieves this after onDataError fires. */
    @JavascriptInterface
    public String getLatestError() {
        return latestError;
    }

    @JavascriptInterface
    public void reset() {
        prefs.edit().clear().apply();
        latestDataJson    = "";
        latestSetupResult = "";
        latestError       = "";
        resetCookies();
    }

    @JavascriptInterface
    public String getLanguage() {
        return prefs.getString(KEY_LANG, "fi");
    }

    @JavascriptInterface
    public void setLanguage(String lang) {
        prefs.edit().putString(KEY_LANG, lang).apply();
    }

    /** Current hour (0–23) in Europe/Helsinki. */
    @JavascriptInterface
    public int getFinnishHour() {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Helsinki"));
        return cal.get(Calendar.HOUR_OF_DAY);
    }

    /** Set screen brightness: -1 = system default, 0 = off, 1 = max. */
    @JavascriptInterface
    public void setScreenBrightness(final float brightness) {
        Activity act = activityRef.get();
        if (act == null) return;
        act.runOnUiThread(new Runnable() {
            @Override public void run() {
                Activity a = activityRef.get();
                if (a == null) return;
                android.view.WindowManager.LayoutParams lp = a.getWindow().getAttributes();
                lp.screenBrightness = brightness;
                a.getWindow().setAttributes(lp);
            }
        });
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    private void doLogin(String baseUrl, String username, String password) throws Exception {
        String loginPage  = httpGet(baseUrl + "/login");
        Map<String, String> fields = parseFormFields(loginPage);

        String sessionId = fields.get("SESSIONID");
        if (sessionId == null || sessionId.isEmpty()) {
            String tokenResp = httpGet(baseUrl + "/token");
            sessionId = parseTokenId(tokenResp);
        }

        StringBuilder body = new StringBuilder();
        body.append("Login=").append(urlEncode(username));
        body.append("&Password=").append(urlEncode(password));
        body.append("&SESSIONID=").append(urlEncode(sessionId));
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (!"SESSIONID".equals(e.getKey()) && !e.getKey().isEmpty()) {
                body.append("&").append(urlEncode(e.getKey()))
                    .append("=").append(urlEncode(e.getValue()));
            }
        }

        HttpURLConnection conn = openConnection(baseUrl + "/login");
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("Referer", baseUrl + "/login");
        conn.setDoOutput(true);
        conn.setInstanceFollowRedirects(false);
        sendCookies(conn, baseUrl);

        byte[] data = body.toString().getBytes("UTF-8");
        conn.setRequestProperty("Content-Length", String.valueOf(data.length));
        OutputStream os = conn.getOutputStream();
        os.write(data); os.flush(); os.close();

        collectCookies(conn, baseUrl);
        readAndClose(conn);

        boolean hasSid = false;
        for (java.net.HttpCookie c : cookieManager.getCookieStore().getCookies()) {
            if ("Wilma2SID".equals(c.getName())) { hasSid = true; break; }
        }
        if (!hasSid) throw new Exception("Kirjautuminen epäonnistui — tarkista tunnus ja salasana");
    }

    private boolean hasSession() {
        for (java.net.HttpCookie c : cookieManager.getCookieStore().getCookies()) {
            if ("Wilma2SID".equals(c.getName())) return true;
        }
        return false;
    }

    // ── Data fetching ─────────────────────────────────────────────────────────

    private String fetchAllStudents() throws Exception {
        String baseUrl  = prefs.getString(KEY_URL,  "");
        String username = prefs.getString(KEY_USER, "");
        String password = prefs.getString(KEY_PASS, "");
        if (baseUrl.isEmpty()) throw new Exception("Not configured");

        // Always start with a fresh login.
        // wilmai does exactly the same: it creates a new cookieless session
        // so that GET / returns the parent home page listing ALL children.
        // Reusing an existing session causes Wilma to redirect / to the
        // last-selected student's page, hiding the other child entirely.
        resetCookies();
        doLogin(baseUrl, username, password);

        // GET / immediately after fresh login — no student pre-selected yet
        String homeHtml = httpGet(baseUrl + "/");
        if (looksLikeLoginPage(homeHtml)) {
            throw new Exception("Kirjautuminen epäonnistui");
        }

        List<String[]> students = parseStudents(homeHtml);
        if (students.isEmpty()) {
            throw new Exception("Oppilaita ei löydy — tarkista Wilma-osoite");
        }

        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Helsinki"));
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        String today    = sdf.format(cal.getTime());
        cal.add(Calendar.DAY_OF_MONTH, 1);
        String tomorrow = sdf.format(cal.getTime());

        JSONArray result = new JSONArray();
        for (String[] student : students) {
            String num  = student[0];
            String name = student[1];
            try {
                String raw = httpGet(baseUrl + "/!" + num + "/overview");
                JSONObject ov = new JSONObject(raw);
                result.put(parseStudentData(ov, name, num, today, tomorrow));
            } catch (Exception e) {
                JSONObject stub = new JSONObject();
                stub.put("student",          name);
                stub.put("studentNumber",    num);
                stub.put("todaySchedule",    new JSONArray());
                stub.put("tomorrowSchedule", new JSONArray());
                stub.put("upcomingExams",    new JSONArray());
                stub.put("recentHomework",   new JSONArray());
                result.put(stub);
            }
        }
        return result.toString();
    }

    private boolean looksLikeLoginPage(String html) {
        if (html == null) return true;
        return html.contains("SESSIONID") && html.contains("<form") && !html.contains("overview");
    }

    // ── Overview parsing ──────────────────────────────────────────────────────

    private JSONObject parseStudentData(JSONObject overview, String name, String number,
                                        String today, String tomorrow) throws Exception {
        JSONObject out = new JSONObject();
        out.put("student",       name);
        out.put("studentNumber", number);

        JSONArray todaySched    = new JSONArray();
        JSONArray tomorrowSched = new JSONArray();
        JSONArray schedRaw      = overview.optJSONArray("Schedule");
        if (schedRaw != null) {
            for (int i = 0; i < schedRaw.length(); i++) {
                JSONObject entry = schedRaw.getJSONObject(i);
                String start     = entry.optString("Start", "");
                String end       = entry.optString("End",   "");
                JSONArray dates  = entry.optJSONArray("DateArray");
                JSONArray groups = entry.optJSONArray("Groups");
                if (dates == null || groups == null || groups.length() == 0) continue;
                JSONObject grp = groups.getJSONObject(0);
                String subj = grp.optString("FullCaption",
                              grp.optString("Caption",
                              grp.optString("ShortCaption", "")));
                if (subj.isEmpty()) continue;
                for (int d = 0; d < dates.length(); d++) {
                    String date = dates.getString(d);
                    JSONObject lesson = new JSONObject();
                    lesson.put("start",   start);
                    lesson.put("end",     end);
                    lesson.put("subject", subj);
                    if (today.equals(date))    todaySched.put(lesson);
                    else if (tomorrow.equals(date)) tomorrowSched.put(lesson);
                }
            }
        }
        out.put("todaySchedule",    todaySched);
        out.put("tomorrowSchedule", tomorrowSched);
        out.put("todayDate",    today);     // ISO yyyy-MM-dd, JS formats it
        out.put("tomorrowDate", tomorrow);

        JSONArray examsOut = new JSONArray();
        JSONArray hwOut    = new JSONArray();
        JSONArray groupsRaw = overview.optJSONArray("Groups");
        if (groupsRaw != null) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Calendar ago7 = Calendar.getInstance(TimeZone.getTimeZone("Europe/Helsinki"));
            ago7.add(Calendar.DAY_OF_MONTH, -7);
            String cutoff = sdf.format(ago7.getTime());

            for (int g = 0; g < groupsRaw.length(); g++) {
                JSONObject grp    = groupsRaw.getJSONObject(g);
                String courseName = grp.optString("CourseName", "");

                JSONArray exArr = grp.optJSONArray("Exams");
                if (exArr != null) {
                    for (int e = 0; e < exArr.length(); e++) {
                        JSONObject ex   = exArr.getJSONObject(e);
                        String grade    = ex.optString("Grade","");
                        if (!grade.isEmpty() && !"null".equals(grade)) continue;
                        String rawDate  = ex.optString("Date","");
                        String isoDate  = finnishToIso(rawDate);
                        if (isoDate.isEmpty() || isoDate.compareTo(today) < 0) continue;
                        JSONObject item = new JSONObject();
                        item.put("isoDate", isoDate); // for sorting, removed before output
                        item.put("date",    displayDate(isoDate));
                        item.put("subject", courseName);
                        item.put("name",    ex.optString("Caption", ex.optString("Name","")));
                        String topic = ex.optString("Topic","");
                        if (!topic.isEmpty()) item.put("topic", topic);
                        examsOut.put(item);
                    }
                }

                JSONArray hwArr = grp.optJSONArray("Homework");
                if (hwArr != null) {
                    for (int h = 0; h < hwArr.length(); h++) {
                        JSONObject hw   = hwArr.getJSONObject(h);
                        String hwDate   = hw.optString("Date","");
                        String hwText   = hw.optString("Homework","").trim();
                        if (hwText.isEmpty()) continue;
                        if (!hwDate.isEmpty() && hwDate.compareTo(cutoff) < 0) continue;
                        JSONObject item = new JSONObject();
                        item.put("date",        hwDate);
                        item.put("subject",     courseName);
                        item.put("description", hwText);
                        hwOut.put(item);
                    }
                }
            }
        }
        // Sort exams by date ascending (nearest first)
        java.util.List<JSONObject> examList = new java.util.ArrayList<>();
        for (int i = 0; i < examsOut.length(); i++) {
            examList.add(examsOut.getJSONObject(i));
        }
        java.util.Collections.sort(examList, new java.util.Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                return a.optString("isoDate","").compareTo(b.optString("isoDate",""));
            }
        });
        JSONArray sortedExams = new JSONArray();
        for (JSONObject ex : examList) {
            ex.remove("isoDate"); // remove sorting helper before sending to JS
            sortedExams.put(ex);
        }
        out.put("upcomingExams",  sortedExams);
        out.put("recentHomework", hwOut);
        return out;
    }

    // ── HTML parsers ──────────────────────────────────────────────────────────

    private List<String[]> parseStudents(String html) {
        List<String[]> list = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();

        // Match <a href="...!/NUMBER/..."> or <a href="...!/NUMBER">
        // Trailing slash after number is optional — some Wilma pages omit it.
        Pattern p = Pattern.compile(
            "<a\\b[^>]*href=['\"]([^'\"]*)/!(\\d+)(?:/[^'\"]*)?['\"][^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher m = p.matcher(html);
        while (m.find()) {
            String num     = m.group(2);
            String rawText = m.group(3);
            String name    = rawText.replaceAll("<[^>]+>","").trim()
                .replace("&amp;","&").replace("&lt;","<")
                .replace("&gt;",">").replace("&nbsp;"," ").trim();
            // Collapse whitespace (multi-line anchor text)
            name = name.replaceAll("\\s+", " ").trim();
            if (name.isEmpty() || seen.contains(num)) continue;
            String lower = name.toLowerCase(Locale.ROOT);
            boolean nav = false;
            for (String kw : NAV_KEYWORDS) { if (lower.contains(kw)) { nav = true; break; } }
            if (!nav) { seen.add(num); list.add(new String[]{num, name}); }
        }
        return list;
    }

    private Map<String, String> parseFormFields(String html) {
        Map<String, String> f = new java.util.LinkedHashMap<>();
        Pattern p = Pattern.compile("<input[^>]+>", Pattern.CASE_INSENSITIVE);
        Pattern namePat = Pattern.compile("name=['\"]([^'\"]+)['\"]",  Pattern.CASE_INSENSITIVE);
        Pattern valPat  = Pattern.compile("value=['\"]([^'\"]*)['\"]", Pattern.CASE_INSENSITIVE);
        Pattern typePat = Pattern.compile("type=['\"]([^'\"]+)['\"]",  Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        while (m.find()) {
            String tag = m.group();
            Matcher nm = namePat.matcher(tag); if (!nm.find()) continue;
            String name = nm.group(1);
            if ("Login".equalsIgnoreCase(name) || "Password".equalsIgnoreCase(name)) continue;
            Matcher tm = typePat.matcher(tag);
            String type = tm.find() ? tm.group(1).toLowerCase(Locale.ROOT) : "text";
            if (!"hidden".equals(type) && !"submit".equals(type)) continue;
            Matcher vm = valPat.matcher(tag);
            f.put(name, vm.find() ? vm.group(1) : "");
        }
        return f;
    }

    private String parseTokenId(String json) throws Exception {
        try {
            JSONObject obj = new JSONObject(json);
            if (obj.has("Wilma2LoginID")) return obj.getString("Wilma2LoginID");
        } catch (Exception ignored) {}
        Pattern p = Pattern.compile("\"Wilma2LoginID\"\\s*:\\s*\"([^\"\\s]+)\"");
        Matcher m = p.matcher(json);
        if (m.find()) return m.group(1);
        throw new Exception("Wilma2LoginID not found in /token response");
    }

    // ── Date helpers ──────────────────────────────────────────────────────────

    private String finnishToIso(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        if (raw.matches("\\d{4}-\\d{2}-\\d{2}")) return raw;
        Matcher m = Pattern.compile("^(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})$").matcher(raw.trim());
        if (m.find()) return String.format(Locale.US, "%s-%02d-%02d",
            m.group(3), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
        return raw;
    }

    private String displayDate(String iso) {
        if (iso == null || iso.length() < 10) return iso;
        try {
            int y = Integer.parseInt(iso.substring(0,4));
            int mo = Integer.parseInt(iso.substring(5,7));
            int d  = Integer.parseInt(iso.substring(8,10));
            int curY = Calendar.getInstance().get(Calendar.YEAR);
            return (y == curY) ? d+"."+mo+"." : d+"."+mo+"."+y;
        } catch (Exception e) { return iso; }
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private String httpGet(String urlStr) throws IOException {
        HttpURLConnection conn = openConnection(urlStr);
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(true);
        sendCookies(conn, urlStr);
        collectCookies(conn, urlStr);
        return readAndClose(conn);
    }

    private HttpURLConnection openConnection(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "text/html,application/json,*/*");
        conn.setRequestProperty("Accept-Charset", "UTF-8");
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(25000);
        return conn;
    }

    private void sendCookies(HttpURLConnection conn, String urlStr) {
        try {
            Map<String, List<String>> h = cookieManager.get(
                new URL(urlStr).toURI(), new java.util.HashMap<String, List<String>>());
            List<String> cl = h.get("Cookie");
            if (cl != null && !cl.isEmpty()) conn.setRequestProperty("Cookie", cl.get(0));
        } catch (Exception ignored) {}
    }

    private void collectCookies(HttpURLConnection conn, String urlStr) {
        try { cookieManager.put(new URL(urlStr).toURI(), conn.getHeaderFields()); }
        catch (Exception ignored) {}
    }

    private String readAndClose(HttpURLConnection conn) throws IOException {
        int status = conn.getResponseCode();
        InputStream is = (status >= 400) ? conn.getErrorStream() : conn.getInputStream();
        if (is == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        r.close(); conn.disconnect();
        return sb.toString();
    }

    private void resetCookies() { cookieManager.getCookieStore().removeAll(); }

    // ── TLS 1.2 socket factory ────────────────────────────────────────────────

    private static class Tls12SocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory d;
        Tls12SocketFactory(SSLSocketFactory d) { this.d = d; }
        private static Socket patch(Socket s) {
            if (s instanceof SSLSocket)
                ((SSLSocket)s).setEnabledProtocols(new String[]{"TLSv1.2","TLSv1.1","TLSv1"});
            return s;
        }
        @Override public String[] getDefaultCipherSuites()  { return d.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites(){ return d.getSupportedCipherSuites(); }
        @Override public Socket createSocket() throws IOException { return patch(d.createSocket()); }
        @Override public Socket createSocket(Socket s,String h,int p,boolean a) throws IOException { return patch(d.createSocket(s,h,p,a)); }
        @Override public Socket createSocket(String h,int p) throws IOException { return patch(d.createSocket(h,p)); }
        @Override public Socket createSocket(String h,int p,InetAddress la,int lp) throws IOException { return patch(d.createSocket(h,p,la,lp)); }
        @Override public Socket createSocket(InetAddress h,int p) throws IOException { return patch(d.createSocket(h,p)); }
        @Override public Socket createSocket(InetAddress h,int p,InetAddress la,int lp) throws IOException { return patch(d.createSocket(h,p,la,lp)); }
    }

    // ── Callback helpers ──────────────────────────────────────────────────────

    /** Post a SHORT signal to JS — no data, no encoding issues. */
    private void signal(final String jsCall) {
        WebView wv = webViewRef.get();
        if (wv == null) return;
        wv.post(new Runnable() {
            @Override public void run() {
                WebView w = webViewRef.get();
                if (w != null) w.loadUrl("javascript:" + jsCall);
            }
        });
    }

    // ── Misc helpers ──────────────────────────────────────────────────────────

    /** Escape for embedding in a JSON string value (not a JS string). */
    private static String escJson(String s) {
        if (s == null) return "";
        return s.replace("\\","\\\\").replace("\"","\\\"")
                .replace("\n","\\n").replace("\r","");
    }

    private static String urlEncode(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }
}
