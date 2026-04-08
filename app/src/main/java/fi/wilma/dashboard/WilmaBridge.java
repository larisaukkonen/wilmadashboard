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
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
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

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.Socket;
import java.io.OutputStream;
import java.net.InetAddress;

/**
 * WilmaBridge — JavascriptInterface between the dashboard HTML and Wilma's HTTP API.
 *
 * Async methods call back into JavaScript via window.onSetupDone / window.onDataReady / etc.
 * Sync methods return values directly.
 */
public class WilmaBridge {

    private static final String PREFS             = "WilmaDashboard";
    private static final String KEY_URL           = "tenantUrl";
    private static final String KEY_NAME          = "tenantName";
    private static final String KEY_USER          = "username";
    private static final String KEY_PASS          = "password";
    private static final String KEY_LANG          = "language";
    private static final String KEY_CACHE         = "cachedData";

    private static final String USER_AGENT        =
        "Mozilla/5.0 (Linux; Android 4.2.2; GT-P3110) " +
        "AppleWebKit/534.30 (KHTML, like Gecko) " +
        "Version/4.0 Mobile Safari/534.30";

    private static final String[] NAV_KEYWORDS = {
        "messages", "viestit", "schedule", "lukujärjestys",
        "gradebook", "assessments", "exams", "attendance",
        "poissaolot", "printouts", "news"
    };

    // Shared cookie jar for the session
    private static CookieManager cookieManager;

    static {
        cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        java.net.CookieHandler.setDefault(cookieManager);
    }

    private final WeakReference<WebView>  webViewRef;
    private final WeakReference<Activity> activityRef;
    private final SharedPreferences       prefs;

    public WilmaBridge(WebView webView, Activity activity) {
        this.webViewRef   = new WeakReference<>(webView);
        this.activityRef  = new WeakReference<>(activity);
        this.prefs        = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        // Enable TLS 1.2 on old Android (API 16-20)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            enableTls12();
        }
    }

    // ── Public interface ───────────────────────────────────────────────────────

    @JavascriptInterface
    public boolean hasCredentials() {
        return !prefs.getString(KEY_URL, "").isEmpty()
            && !prefs.getString(KEY_USER, "").isEmpty()
            && !prefs.getString(KEY_PASS, "").isEmpty();
    }

    /** Save credentials and attempt login. Calls window.onSetupDone({ok,error}). */
    @JavascriptInterface
    public void setup(final String tenantUrl, final String tenantName,
                      final String username,  final String password) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String cleanUrl = tenantUrl.trim().replaceAll("/+$", "");
                    resetCookies();
                    doLogin(cleanUrl, username, password);

                    // Persist only after successful login
                    prefs.edit()
                        .putString(KEY_URL,  cleanUrl)
                        .putString(KEY_NAME, tenantName.trim())
                        .putString(KEY_USER, username.trim())
                        .putString(KEY_PASS, password)
                        .apply();

                    callJs("window.onSetupDone('{\"ok\":true}')");
                } catch (Exception e) {
                    String msg = e.getMessage() != null ? e.getMessage() : "Unknown error";
                    callJs("window.onSetupDone('{\"error\":\"" + escJs(msg) + "\"}')");
                }
            }
        }).start();
    }

    /** Return cached data JSON immediately (may be empty string). */
    @JavascriptInterface
    public String getCachedData() {
        return prefs.getString(KEY_CACHE, "");
    }

    /** Fetch fresh data from Wilma. Calls window.onDataReady(json) or window.onDataError(msg). */
    @JavascriptInterface
    public void fetchData() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String json = fetchAllStudents();
                    prefs.edit().putString(KEY_CACHE, json).apply();
                    callJs("window.onDataReady('" + escJs(json) + "')");
                } catch (Exception e) {
                    String msg = e.getMessage() != null ? e.getMessage() : "Fetch failed";
                    callJs("window.onDataError('" + escJs(msg) + "')");
                }
            }
        }).start();
    }

    /** Clear all saved credentials and cached data. */
    @JavascriptInterface
    public void reset() {
        prefs.edit().clear().apply();
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

    /** Current hour (0-23) in Europe/Helsinki timezone. */
    @JavascriptInterface
    public int getFinnishHour() {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Helsinki"));
        return cal.get(Calendar.HOUR_OF_DAY);
    }

    /** Set screen brightness. -1 = system default, 0.0 = off, 1.0 = max. */
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

    // ── Login ──────────────────────────────────────────────────────────────────

    private void doLogin(String baseUrl, String username, String password) throws Exception {
        // Step 1: GET /login to collect hidden form fields
        String loginPage = httpGet(baseUrl + "/login");
        Map<String, String> fields = parseFormFields(loginPage);

        // Step 2: If no SESSIONID in form, GET /token
        String sessionId = fields.get("SESSIONID");
        if (sessionId == null || sessionId.isEmpty()) {
            String tokenJson = httpGet(baseUrl + "/token");
            sessionId = parseTokenId(tokenJson);
        }

        // Step 3: POST login
        StringBuilder body = new StringBuilder();
        body.append("Login=").append(urlEncode(username));
        body.append("&Password=").append(urlEncode(password));
        body.append("&SESSIONID=").append(urlEncode(sessionId));
        // Include other hidden fields (e.g. formkey)
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (!e.getKey().equals("SESSIONID") && !e.getKey().isEmpty()) {
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

        byte[] postData = body.toString().getBytes("UTF-8");
        conn.setRequestProperty("Content-Length", String.valueOf(postData.length));
        OutputStream os = conn.getOutputStream();
        os.write(postData);
        os.flush();
        os.close();

        int status = conn.getResponseCode();
        collectCookies(conn, baseUrl);
        readAndClose(conn);

        // Check for Wilma2SID cookie
        List<java.net.HttpCookie> cookies =
            cookieManager.getCookieStore().getCookies();
        boolean hasSid = false;
        for (java.net.HttpCookie c : cookies) {
            if ("Wilma2SID".equals(c.getName())) { hasSid = true; break; }
        }
        if (!hasSid && (status < 300 || status >= 400)) {
            throw new Exception("Login failed — check your credentials");
        }
    }

    // ── Data fetching ──────────────────────────────────────────────────────────

    private String fetchAllStudents() throws Exception {
        String baseUrl  = prefs.getString(KEY_URL,  "");
        String username = prefs.getString(KEY_USER, "");
        String password = prefs.getString(KEY_PASS, "");

        if (baseUrl.isEmpty()) throw new Exception("Not configured");

        // Re-login if session is lost
        resetCookies();
        doLogin(baseUrl, username, password);

        // Get student list from home page
        String homeHtml = httpGet(baseUrl + "/");
        List<String[]> students = parseStudents(homeHtml);

        if (students.isEmpty()) {
            throw new Exception("No students found");
        }

        // Today and tomorrow in Finnish time (ISO format)
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Helsinki"));
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        String today = sdf.format(cal.getTime());
        cal.add(Calendar.DAY_OF_MONTH, 1);
        String tomorrow = sdf.format(cal.getTime());

        JSONArray result = new JSONArray();

        for (String[] student : students) {
            String number = student[0]; // e.g. "12345"
            String name   = student[1]; // e.g. "Aino Mäkinen"

            try {
                String overviewJson = httpGet(baseUrl + "/!" + number + "/overview");
                JSONObject overview = new JSONObject(overviewJson);
                JSONObject parsed   = parseStudentData(overview, name, number, today, tomorrow);
                result.put(parsed);
            } catch (Exception e) {
                // If one student fails, add a placeholder so we still show others
                JSONObject err = new JSONObject();
                err.put("student", name);
                err.put("studentNumber", number);
                err.put("todaySchedule", new JSONArray());
                err.put("tomorrowSchedule", new JSONArray());
                err.put("upcomingExams", new JSONArray());
                err.put("recentHomework", new JSONArray());
                result.put(err);
            }
        }

        return result.toString();
    }

    // ── Overview parsing ───────────────────────────────────────────────────────

    private JSONObject parseStudentData(JSONObject overview, String name, String number,
                                        String today, String tomorrow) throws Exception {
        JSONObject out = new JSONObject();
        out.put("student", name);
        out.put("studentNumber", number);

        // Schedule
        JSONArray todaySched    = new JSONArray();
        JSONArray tomorrowSched = new JSONArray();
        JSONArray scheduleRaw   = overview.optJSONArray("Schedule");
        if (scheduleRaw != null) {
            for (int i = 0; i < scheduleRaw.length(); i++) {
                JSONObject entry  = scheduleRaw.getJSONObject(i);
                String start      = entry.optString("Start", "");
                String end        = entry.optString("End", "");
                JSONArray dates   = entry.optJSONArray("DateArray");
                JSONArray groups  = entry.optJSONArray("Groups");
                if (dates == null || groups == null) continue;

                // Get subject from first group
                String subject = "";
                if (groups.length() > 0) {
                    JSONObject grp = groups.getJSONObject(0);
                    subject = grp.optString("FullCaption",
                              grp.optString("Caption",
                              grp.optString("ShortCaption", "")));
                }
                if (subject.isEmpty()) continue;

                for (int d = 0; d < dates.length(); d++) {
                    String date = dates.getString(d);
                    JSONObject lesson = new JSONObject();
                    lesson.put("start", start);
                    lesson.put("end",   end);
                    lesson.put("subject", subject);

                    if (today.equals(date)) {
                        todaySched.put(lesson);
                    } else if (tomorrow.equals(date)) {
                        tomorrowSched.put(lesson);
                    }
                }
            }
        }
        out.put("todaySchedule",    todaySched);
        out.put("tomorrowSchedule", tomorrowSched);

        // Upcoming exams and recent homework from Groups
        JSONArray upcomingExams   = new JSONArray();
        JSONArray recentHomework  = new JSONArray();
        JSONArray groupsRaw       = overview.optJSONArray("Groups");

        if (groupsRaw != null) {
            for (int g = 0; g < groupsRaw.length(); g++) {
                JSONObject group    = groupsRaw.getJSONObject(g);
                String courseName   = group.optString("CourseName", "");

                // Exams
                JSONArray examsArr = group.optJSONArray("Exams");
                if (examsArr != null) {
                    for (int e = 0; e < examsArr.length(); e++) {
                        JSONObject exam = examsArr.getJSONObject(e);
                        // Skip already graded exams
                        String grade = exam.optString("Grade", "");
                        if (!grade.isEmpty() && !grade.equals("null")) continue;

                        String rawDate = exam.optString("Date", "");
                        String isoDate = finnishToIso(rawDate);
                        if (isoDate.isEmpty() || isoDate.compareTo(today) < 0) continue;

                        JSONObject ex = new JSONObject();
                        ex.put("date",    formatDisplayDate(isoDate));
                        ex.put("subject", courseName);
                        ex.put("name",    exam.optString("Caption",
                                          exam.optString("Name", "")));
                        String topic = exam.optString("Topic", "");
                        if (!topic.isEmpty()) ex.put("topic", topic);
                        upcomingExams.put(ex);
                    }
                }

                // Homework (last 7 days)
                JSONArray hwArr = group.optJSONArray("Homework");
                if (hwArr != null) {
                    Calendar sevenDaysAgo = Calendar.getInstance(
                        TimeZone.getTimeZone("Europe/Helsinki"));
                    sevenDaysAgo.add(Calendar.DAY_OF_MONTH, -7);
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                    String cutoff = sdf.format(sevenDaysAgo.getTime());

                    for (int h = 0; h < hwArr.length(); h++) {
                        JSONObject hw   = hwArr.getJSONObject(h);
                        String hwDate   = hw.optString("Date", "");
                        String hwText   = hw.optString("Homework", "").trim();
                        if (hwText.isEmpty()) continue;
                        if (!hwDate.isEmpty() && hwDate.compareTo(cutoff) < 0) continue;

                        JSONObject item = new JSONObject();
                        item.put("date",        hwDate);
                        item.put("subject",     courseName);
                        item.put("description", hwText);
                        recentHomework.put(item);
                    }
                }
            }
        }

        out.put("upcomingExams",  upcomingExams);
        out.put("recentHomework", recentHomework);
        return out;
    }

    // ── HTML parsers ───────────────────────────────────────────────────────────

    /**
     * Parse student list from Wilma home page.
     * Returns list of String[]{studentNumber, name}.
     */
    private List<String[]> parseStudents(String html) {
        List<String[]> students = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();

        // Match <a href="/!NUMBER/...">NAME</a>
        Pattern p = Pattern.compile(
            "<a[^>]+href=['\"](?:[^'\"]*)/!(\\d+)/[^'\"]*['\"][^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher m = p.matcher(html);

        while (m.find()) {
            String number = m.group(1);
            String raw    = m.group(2);
            // Strip inner HTML tags
            String name   = raw.replaceAll("<[^>]+>", "").trim();
            // Decode common HTML entities
            name = name.replace("&amp;", "&").replace("&lt;", "<")
                       .replace("&gt;", ">").replace("&nbsp;", " ").trim();
            if (name.isEmpty()) continue;
            // Filter out navigation items
            String lower = name.toLowerCase(Locale.ROOT);
            boolean isNav = false;
            for (String kw : NAV_KEYWORDS) {
                if (lower.contains(kw)) { isNav = true; break; }
            }
            if (isNav) continue;
            if (!seen.contains(number)) {
                seen.add(number);
                students.add(new String[]{number, name});
            }
        }
        return students;
    }

    /** Parse hidden input fields from login form HTML. */
    private Map<String, String> parseFormFields(String html) {
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        Pattern p = Pattern.compile("<input[^>]+>", Pattern.CASE_INSENSITIVE);
        Pattern namePat  = Pattern.compile("name=['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE);
        Pattern valPat   = Pattern.compile("value=['\"]([^'\"]*)['\"]", Pattern.CASE_INSENSITIVE);
        Pattern typePat  = Pattern.compile("type=['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        while (m.find()) {
            String tag = m.group();
            Matcher nm = namePat.matcher(tag);
            if (!nm.find()) continue;
            String name = nm.group(1);
            if ("Login".equalsIgnoreCase(name) || "Password".equalsIgnoreCase(name)) continue;
            Matcher tm = typePat.matcher(tag);
            String type = tm.find() ? tm.group(1).toLowerCase(Locale.ROOT) : "text";
            if (!"hidden".equals(type) && !"submit".equals(type)) continue;
            Matcher vm = valPat.matcher(tag);
            String value = vm.find() ? vm.group(1) : "";
            fields.put(name, value);
        }
        return fields;
    }

    /** Extract Wilma2LoginID from the /token JSON response. */
    private String parseTokenId(String json) throws Exception {
        try {
            JSONObject obj = new JSONObject(json);
            if (obj.has("Wilma2LoginID")) return obj.getString("Wilma2LoginID");
        } catch (Exception ignored) {}
        Pattern p = Pattern.compile("\"Wilma2LoginID\"\\s*:\\s*\"([^\"\\s]+)\"");
        Matcher m = p.matcher(json);
        if (m.find()) return m.group(1);
        throw new Exception("Could not find Wilma2LoginID in /token response");
    }

    // ── Date helpers ───────────────────────────────────────────────────────────

    /** Convert Finnish date "14.4.2025" to ISO "2025-04-14". */
    private String finnishToIso(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        // Already ISO?
        if (raw.matches("\\d{4}-\\d{2}-\\d{2}")) return raw;
        Pattern p = Pattern.compile("^(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})$");
        Matcher m = p.matcher(raw.trim());
        if (m.find()) {
            return String.format(Locale.US, "%s-%02d-%02d",
                m.group(3),
                Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(1)));
        }
        return raw;
    }

    /** Format ISO date "2025-04-14" → "14.4." for display. */
    private String formatDisplayDate(String iso) {
        if (iso == null || iso.length() < 10) return iso;
        try {
            int year  = Integer.parseInt(iso.substring(0, 4));
            int month = Integer.parseInt(iso.substring(5, 7));
            int day   = Integer.parseInt(iso.substring(8, 10));
            // Show year only if not current year
            Calendar cal = Calendar.getInstance();
            if (year == cal.get(Calendar.YEAR)) {
                return day + "." + month + ".";
            }
            return day + "." + month + "." + year;
        } catch (Exception e) {
            return iso;
        }
    }

    // ── HTTP helpers ───────────────────────────────────────────────────────────

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
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        return conn;
    }

    private void sendCookies(HttpURLConnection conn, String urlStr) {
        try {
            Map<String, List<String>> cookies =
                cookieManager.get(new URL(urlStr).toURI(),
                    new java.util.HashMap<String, List<String>>());
            List<String> cookieList = cookies.get("Cookie");
            if (cookieList != null && !cookieList.isEmpty()) {
                conn.setRequestProperty("Cookie", cookieList.get(0));
            }
        } catch (Exception ignored) {}
    }

    private void collectCookies(HttpURLConnection conn, String urlStr) {
        try {
            cookieManager.put(new URL(urlStr).toURI(), conn.getHeaderFields());
        } catch (Exception ignored) {}
    }

    private String readAndClose(HttpURLConnection conn) throws IOException {
        int status = conn.getResponseCode();
        InputStream is = (status >= 400) ? conn.getErrorStream() : conn.getInputStream();
        if (is == null) return "";
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append('\n');
        }
        reader.close();
        conn.disconnect();
        return sb.toString();
    }

    private void resetCookies() {
        cookieManager.getCookieStore().removeAll();
    }

    // ── TLS 1.2 for Android 4.x ───────────────────────────────────────────────

    private void enableTls12() {
        try {
            SSLContext sc = SSLContext.getInstance("TLSv1.2");
            sc.init(null, null, null);
            HttpsURLConnection.setDefaultSSLSocketFactory(
                new Tls12SocketFactory(sc.getSocketFactory()));
        } catch (Exception ignored) {}
    }

    private static class Tls12SocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;
        Tls12SocketFactory(SSLSocketFactory delegate) { this.delegate = delegate; }

        private static Socket patch(Socket s) {
            if (s instanceof SSLSocket) {
                ((SSLSocket) s).setEnabledProtocols(
                    new String[]{"TLSv1.2", "TLSv1.1", "TLSv1"});
            }
            return s;
        }

        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        @Override public Socket createSocket() throws IOException { return patch(delegate.createSocket()); }
        @Override public Socket createSocket(Socket s, String h, int port, boolean auto) throws IOException { return patch(delegate.createSocket(s, h, port, auto)); }
        @Override public Socket createSocket(String h, int port) throws IOException { return patch(delegate.createSocket(h, port)); }
        @Override public Socket createSocket(String h, int port, InetAddress la, int lp) throws IOException { return patch(delegate.createSocket(h, port, la, lp)); }
        @Override public Socket createSocket(InetAddress h, int port) throws IOException { return patch(delegate.createSocket(h, port)); }
        @Override public Socket createSocket(InetAddress h, int port, InetAddress la, int lp) throws IOException { return patch(delegate.createSocket(h, port, la, lp)); }
    }

    // ── Misc helpers ───────────────────────────────────────────────────────────

    private void callJs(final String script) {
        WebView wv = webViewRef.get();
        if (wv == null) return;
        wv.post(new Runnable() {
            @Override public void run() {
                WebView w = webViewRef.get();
                if (w != null) w.loadUrl("javascript:" + script);
            }
        });
    }

    private static String escJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("'",  "\\'")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
