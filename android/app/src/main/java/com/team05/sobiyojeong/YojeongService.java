package com.team05.sobiyojeong;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Watches for the user's chosen shopping apps and shows the assigned fairy on top of them:
 * when the app opens, when something is added to the cart, and when a checkout button is tapped.
 */
public class YojeongService extends AccessibilityService {

    private static final Pattern CHECKOUT = Pattern.compile(
            "(결제하기|결제|구매하기|구매\\s*하기|바로\\s*구매|주문하기|buy\\s*now|checkout)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CART = Pattern.compile("(장바구니|카트|담기)");

    private WindowManager wm;
    private View overlay;
    private String curPkg = "";
    private String curApp = "쇼핑 앱";
    private String main = "todak";
    private JSONObject curCfg;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Long> lastEnter = new HashMap<>();
    private long lastCheckout = 0, lastCart = 0;
    private Runnable timer;
    private int seq = 0;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onServiceConnected() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public void onInterrupt() {
        hide();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        if (pkg.equals(getPackageName())) return;
        JSONObject c = config();
        if (c == null || c.optString("main", "").isEmpty()) return;
        Set<String> watch = watched(c);
        int type = e.getEventType();

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (isTransient(pkg)) return;
            if (!watch.contains(pkg)) {
                if (!pkg.equals(curPkg)) {
                    curPkg = pkg;
                    hide();
                    cancelTimer();
                }
                return;
            }
            if (!pkg.equals(curPkg)) {
                curPkg = pkg;
                onEnter(c, pkg);
            }
        } else if (type == AccessibilityEvent.TYPE_VIEW_CLICKED && watch.contains(pkg)) {
            String t = textOf(e);
            if (t.isEmpty()) return;
            long now = System.currentTimeMillis();
            if (CHECKOUT.matcher(t).find()) {
                if (now - lastCheckout < 20000) return;
                lastCheckout = now;
                onCheckout(c, pkg);
            } else if (CART.matcher(t).find()) {
                if (now - lastCart < 15000) return;
                lastCart = now;
                onCart(c, pkg);
            }
        }
    }

    // ------------------------------------------------------------------ config helpers

    private JSONObject config() {
        try {
            String s = Store.prefs(this).getString("config", null);
            return s == null ? null : new JSONObject(s);
        } catch (JSONException ex) {
            return null;
        }
    }

    private Set<String> watched(JSONObject c) {
        Set<String> s = new HashSet<>();
        JSONArray a = c.optJSONArray("pkgs");
        if (a != null) for (int i = 0; i < a.length(); i++) s.add(a.optString(i));
        return s;
    }

    private String label(JSONObject c, String pkg) {
        JSONObject l = c.optJSONObject("labels");
        return l != null ? l.optString(pkg, "쇼핑 앱") : "쇼핑 앱";
    }

    private String listText(JSONObject c) {
        JSONArray a = c.optJSONArray("list");
        if (a == null || a.length() == 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(a.optString(i));
        }
        return "처음 목록 · " + sb;
    }

    private boolean isTransient(String pkg) {
        if (pkg.startsWith("com.android.systemui")) return true;
        String ime = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (ime != null && ime.startsWith(pkg + "/")) return true;
        return pkg.contains("inputmethod") || pkg.contains("keyboard");
    }

    private String textOf(AccessibilityEvent e) {
        StringBuilder sb = new StringBuilder();
        for (CharSequence cs : e.getText()) if (cs != null) sb.append(cs).append(' ');
        if (e.getContentDescription() != null) sb.append(e.getContentDescription());
        if (sb.toString().trim().isEmpty()) {
            AccessibilityNodeInfo n = e.getSource();
            if (n != null) collect(n, sb, 0);
        }
        return sb.toString().trim();
    }

    private void collect(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || depth > 3) return;
        if (n.getText() != null) sb.append(n.getText()).append(' ');
        if (n.getContentDescription() != null) sb.append(n.getContentDescription()).append(' ');
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), sb, depth + 1);
    }

    private String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(new Date());
    }

    // ------------------------------------------------------------------ moments

    private void onEnter(JSONObject c, String pkg) {
        long now = System.currentTimeMillis();
        Long last = lastEnter.get(pkg);
        lastEnter.put(pkg, now);
        String app = label(c, pkg);

        JSONObject ev = new JSONObject();
        try {
            ev.put("t", "open");
            ev.put("app", app);
            ev.put("date", today());
        } catch (JSONException ignored) {
        }
        Store.push(this, ev);
        int opens = Store.bumpOpens(this, today());

        String freq = c.optString("freq", "every");
        if (freq.equals("checkout")) return;
        if (freq.equals("daily")) {
            if (today().equals(Store.prefs(this).getString("dailyShown", ""))) return;
            Store.prefs(this).edit().putString("dailyShown", today()).apply();
        }
        if (last != null && now - last < 90000) return;
        begin(c, app);
        render(enterNode(opens, c));
    }

    private void onCheckout(JSONObject c, String pkg) {
        begin(c, label(c, pkg));
        render(checkoutNode(c));
    }

    private void onCart(JSONObject c, String pkg) {
        if (c.optString("freq", "every").equals("checkout")) return;
        begin(c, label(c, pkg));
        Node n = cartNode();
        if (n != null) render(n);
    }

    private void begin(JSONObject c, String app) {
        curCfg = c;
        curApp = app;
        main = c.optString("main", "todak");
    }

    // ------------------------------------------------------------------ dialogue model

    static final class Opt {
        final String label, sub;
        final Runnable act;
        final boolean end;

        Opt(String label, String sub, Runnable act, boolean end) {
            this.label = label;
            this.sub = sub;
            this.act = act;
            this.end = end;
        }
    }

    static final class Node {
        final String line;
        String extra;
        final List<Opt> opts = new ArrayList<>();

        Node(String line) {
            this.line = line;
        }

        Node extra(String x) {
            extra = x;
            return this;
        }

        Node opt(String label, Runnable act) {
            opts.add(new Opt(label, null, act, false));
            return this;
        }

        Node opt(String label, String sub, Runnable act) {
            opts.add(new Opt(label, sub, act, false));
            return this;
        }

        Node skip(String label, Runnable act) {
            opts.add(new Opt(label, null, act, true));
            return this;
        }
    }

    private Runnable say(String msg) {
        return () -> showMsg(msg);
    }

    private Runnable next(Supplier<Node> s) {
        return () -> render(s.get());
    }

    private Runnable decide(String kind, String tag) {
        return () -> {
            JSONObject ev = new JSONObject();
            try {
                ev.put("t", "decision");
                ev.put("kind", kind);
                ev.put("tag", tag);
                ev.put("app", curApp);
                ev.put("date", today());
            } catch (JSONException ignored) {
            }
            Store.push(this, ev);
            if (kind.equals("buy")) {
                showMsg("좋아! 기록해 둘게. 7일 뒤에 잘 쓰고 있는지 물어볼게.");
            } else {
                String l = kind.equals("hold")
                        ? "다시 볼 상품에 기록해 뒀어. 결제 화면에서 나갈까?"
                        : "좋아, 오늘은 사지 않기로! 결제 화면에서 나갈까?";
                render(new Node(l)
                        .opt("응, 나갈래", () -> {
                            hide();
                            performGlobalAction(GLOBAL_ACTION_BACK);
                        })
                        .skip("괜찮아, 내가 할게", this::hide));
            }
        };
    }

    private Node enterNode(int opens, JSONObject c) {
        switch (main) {
            case "gomgom":
                return new Node("오늘 필요한 게 있어? 먼저 떠올려두면 혜택도 알맞게 고를 수 있겠다.")
                        .opt("필요한 게 있어", say("필요한 것부터 찾아보자!"))
                        .opt("세일 보러 왔어", say("좋아. 할인 상품을 담을 때, 정가로도 살지 한 번만 생각해 줘."))
                        .skip("대답 안 할래", this::hide);
            case "meomchit":
                return new Node("오늘 찾는 물건 있어? 뭘 사러 왔는지 하나만 떠올려보자.")
                        .opt("찾는 물건이 있어", say("좋아! 바로 구매를 누르기 전에 언제 쓸지 한 번만 떠올려줘."))
                        .opt("그냥 구경", say("천천히 구경해. 결제할 때 잠깐 나타날게."))
                        .skip("대답 안 할래", this::hide);
            case "swieom":
                return new Node("안녕, 오늘은 찾는 게 있어? 그냥 둘러보러 왔어도 괜찮아.")
                        .extra("오늘 쇼핑 앱을 연 횟수 " + opens + "번째")
                        .opt("그냥 둘러보려고", next(() -> new Node("그럼 오늘은 얼마나 구경하고 싶어?")
                                .opt("10분", () -> startTimer(10))
                                .opt("20분", () -> startTimer(20))
                                .opt("30분", () -> startTimer(30))))
                        .opt("찾는 게 있어", say("찾으면 바로 쉬러 가자!"))
                        .skip("대답 안 할래", this::hide);
            case "ssokdam":
                return new Node("오늘 사러 온 게 뭐야? 같이 기억해두자!")
                        .extra(listText(c))
                        .opt("필요한 것 하나만 살래", say("좋아! 담을 때마다 처음 목적이랑 같이 볼게."))
                        .opt("여러 개 살 거야", say("그럼 담을 때마다 한 번씩 같이 확인하자."))
                        .skip("대답 안 할래", this::hide);
            case "todak":
            default:
                return new Node("왔구나! 오늘은 어떤 기분으로 구경하러 왔어?")
                        .opt("스트레스받아 · 우울해", next(() -> new Node("마음이 좀 힘든 날이구나. 오늘은 너한테 뭔가 해주고 싶었어?")
                                .opt("응, 나한테 선물하고 싶어", say("그 마음도 기록해 둘게. 결제하기 전에 한 번만 더 물어볼게!"))
                                .opt("사실 필요한 게 있어", say("필요한 걸 찾으러 왔구나. 그것부터 찾아보자!"))
                                .skip("지금은 그냥 둘러볼래", this::hide)))
                        .opt("괜찮아 · 기분 좋아", say("좋아! 즐겁게 둘러보고 와."))
                        .skip("대답 안 할래", this::hide);
        }
    }

    private Node checkoutNode(JSONObject c) {
        switch (main) {
            case "gomgom":
                return new Node("할인받는 금액 말고, 실제로 쓸 돈을 봐 줘. 담은 것들은 모두 필요해?")
                        .opt("모두 필요해", "구매", decide("buy", "필요해서"))
                        .opt("일부는 다음에", "보류 · 다시 볼 상품", decide("hold", "할인 때문에"))
                        .opt("오늘은 안 살래", "제외", decide("drop", "할인 때문에"));
            case "meomchit":
                return new Node("결제 전에 한 가지만! 네가 쓰려던 용도에 맞는 물건인지 확인했어?")
                        .opt("확인했어", "구매", decide("buy", "계획한 구매"))
                        .opt("조금 더 생각할래", "보류 · 다시 볼 상품", decide("hold", "둘러보다"))
                        .opt("용도에 안 맞아", "제외", decide("drop", "둘러보다"));
            case "swieom":
                return new Node("둘러보다 고른 물건이네. 지금 필요한 이유가 생겼어?")
                        .opt("응, 이유가 생겼어", "구매", decide("buy", "필요해서"))
                        .opt("아직은 잘 모르겠어", "보류 · 다시 볼 상품", decide("hold", "둘러보다"))
                        .opt("그냥 보던 거야", "제외", decide("drop", "둘러보다"));
            case "ssokdam":
                return new Node("처음 사려던 것 말고 추가한 것도 있어? 오늘 함께 살 이유가 있어?")
                        .extra(listText(c))
                        .opt("모두 살 이유가 있어", "구매", decide("buy", "필요해서"))
                        .opt("추가한 건 다음에", "보류 · 다시 볼 상품", decide("hold", "계획한 구매"))
                        .opt("오늘은 안 살래", "제외", decide("drop", "계획한 구매"));
            case "todak":
            default:
                return new Node("이걸 쓰는 모습도 떠올라, 아니면 지금 기분을 바꾸고 싶은 마음이 더 커?")
                        .opt("쓰는 모습이 떠올라", "구매", decide("buy", "계획한 구매"))
                        .opt("기분을 바꾸고 싶었나 봐", "보류 · 다시 볼 상품", decide("hold", "기분 전환"))
                        .opt("오늘은 안 살래", "제외", decide("drop", "기분 전환"));
        }
    }

    private Node cartNode() {
        switch (main) {
            case "todak":
                return new Node("이게 마음에 들었구나. 어떤 점이 너를 기분 좋게 해줄 것 같아?")
                        .opt("쓸 곳이 떠올라", say("사용 계획을 기억해 둘게!"))
                        .opt("그냥 기분이 좋아질 것 같아", say("결제할 때 한 번만 더 물어볼게."))
                        .skip("잘 모르겠어", this::hide);
            case "ssokdam":
                return new Node("이것도 발견했네! 처음 사려던 물건이랑 같이 필요한 거야?")
                        .opt("같이 필요해", say("추가한 상품으로 기억해 둘게."))
                        .opt("나중에 봐도 돼", say("장바구니에서 빼고 찜해 두는 건 어때?"))
                        .skip("닫기", this::hide);
            case "gomgom":
                return new Node("무료배송이나 쿠폰 조건을 채우려고 담은 거야?")
                        .opt("아니, 필요해서", say("좋아, 필요한 거라면 괜찮아!"))
                        .opt("응, 조건 채우려고", say("더 담는 금액이 아끼는 배송비보다 큰지 꼭 비교해 봐!"))
                        .skip("닫기", this::hide);
            default:
                return null;
        }
    }

    private Node timerNode() {
        return new Node("우리 정한 시간이 됐네. 더 보고 싶은 게 있어, 아니면 여기까지 볼까?")
                .opt("조금 더 볼래", "10분 연장", () -> startTimer(10))
                .opt("여기까지 볼래", "홈 화면으로", () -> {
                    hide();
                    performGlobalAction(GLOBAL_ACTION_HOME);
                })
                .skip("닫기", this::hide);
    }

    private void startTimer(int minutes) {
        cancelTimer();
        final String pkgAtStart = curPkg;
        timer = () -> {
            if (pkgAtStart.equals(curPkg)) render(timerNode());
        };
        handler.postDelayed(timer, minutes * 60000L);
        showMsg(minutes + "분 뒤에 알려줄게!");
    }

    private void cancelTimer() {
        if (timer != null) handler.removeCallbacks(timer);
        timer = null;
    }

    // ------------------------------------------------------------------ overlay UI

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private int color() {
        switch (main) {
            case "gomgom": return 0xFF8A68DD;
            case "meomchit": return 0xFF45A852;
            case "swieom": return 0xFFA06C4B;
            case "ssokdam": return 0xFFEC7430;
            default: return 0xFFE9A61E;
        }
    }

    private String nameType() {
        switch (main) {
            case "gomgom": return "곰곰이 · 혜택 반응형";
            case "meomchit": return "멈칫이 · 즉시 구매형";
            case "swieom": return "쉬엄이 · 습관 반복형";
            case "ssokdam": return "쏙담이 · 연쇄 구매형";
            default: return "토닥이 · 감정 전환형";
        }
    }

    private int image() {
        switch (main) {
            case "gomgom": return R.drawable.c_gomgom;
            case "meomchit": return R.drawable.c_meomchit;
            case "swieom": return R.drawable.c_swieom;
            case "ssokdam": return R.drawable.c_ssokdam;
            default: return R.drawable.c_todak;
        }
    }

    private static int withAlpha(int c, int alpha) {
        return (c & 0x00FFFFFF) | (alpha << 24);
    }

    private static int mix(int c, float amount) {
        int r = (int) (255 + (Color.red(c) - 255) * amount);
        int g = (int) (255 + (Color.green(c) - 255) * amount);
        int b = (int) (255 + (Color.blue(c) - 255) * amount);
        return Color.rgb(r, g, b);
    }

    private GradientDrawable round(int fill, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private void showMsg(String msg) {
        render(new Node(msg).skip("확인", this::hide));
        final int mine = seq;
        handler.postDelayed(() -> {
            if (seq == mine) hide();
        }, 4500);
    }

    private void render(Node n) {
        if (wm == null || n == null) return;
        hide();
        seq++;
        int col = color();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.END);

        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(dp(16), dp(14), dp(16), dp(12));
        GradientDrawable bg = round(Color.WHITE, 22);
        bg.setStroke(dp(1.5f), withAlpha(col, 0x80));
        bubble.setBackground(bg);
        bubble.setElevation(dp(10));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView chip = new TextView(this);
        chip.setText(nameType());
        chip.setTextSize(12);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setTextColor(darker(col));
        chip.setPadding(dp(10), dp(4), dp(10), dp(4));
        chip.setBackground(round(mix(col, 0.16f), 12));
        head.addView(chip);
        View spacer = new View(this);
        head.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextSize(15);
        close.setTextColor(0xFFB8B9CC);
        close.setPadding(dp(8), dp(2), dp(2), dp(2));
        close.setOnClickListener(v -> hide());
        head.addView(close);
        bubble.addView(head);

        TextView line = new TextView(this);
        line.setText(n.line);
        line.setTextSize(16);
        line.setTypeface(Typeface.DEFAULT_BOLD);
        line.setTextColor(0xFF1A1A2E);
        line.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        bubble.addView(line, lp);

        if (n.extra != null && !n.extra.isEmpty()) {
            TextView ex = new TextView(this);
            ex.setText(n.extra);
            ex.setTextSize(13);
            ex.setTextColor(0xFF4F5066);
            ex.setPadding(dp(12), dp(10), dp(12), dp(10));
            ex.setBackground(round(0xFFF3F3F8, 12));
            LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, -2);
            ep.topMargin = dp(10);
            bubble.addView(ex, ep);
        }

        for (int i = 0; i < n.opts.size(); i++) {
            Opt o = n.opts.get(i);
            TextView b = new TextView(this);
            b.setText(o.sub != null ? o.label + "   ·  " + o.sub : o.label);
            b.setTextSize(14.5f);
            b.setLineSpacing(0, 1.1f);
            if (o.end) {
                b.setTextColor(0xFF8C8DA3);
                b.setGravity(Gravity.CENTER);
                b.setPadding(dp(12), dp(8), dp(12), dp(4));
            } else {
                b.setTypeface(Typeface.DEFAULT_BOLD);
                b.setTextColor(0xFF1A1A2E);
                b.setPadding(dp(14), dp(12), dp(14), dp(12));
                b.setBackground(round(i == 0 ? mix(col, 0.18f) : 0xFFF3F3F8, 14));
            }
            final Runnable act = o.act;
            b.setOnClickListener(v -> act.run());
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
            bp.topMargin = dp(i == 0 ? 12 : 6);
            bubble.addView(b, bp);
        }

        root.addView(bubble, new LinearLayout.LayoutParams(dp(300), -2));

        ImageView img = new ImageView(this);
        img.setImageResource(image());
        img.setAdjustViewBounds(true);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(96), dp(96));
        ip.topMargin = -dp(6);
        ip.rightMargin = dp(4);
        root.addView(img, ip);

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.BOTTOM | Gravity.END;
        p.x = dp(10);
        p.y = dp(90);
        try {
            wm.addView(root, p);
            overlay = root;
        } catch (Exception ignored) {
            overlay = null;
        }
    }

    private static int darker(int c) {
        return Color.rgb((int) (Color.red(c) * 0.62f), (int) (Color.green(c) * 0.62f), (int) (Color.blue(c) * 0.62f));
    }

    private void hide() {
        if (overlay != null && wm != null) {
            try {
                wm.removeView(overlay);
            } catch (Exception ignored) {
            }
        }
        overlay = null;
    }
}
