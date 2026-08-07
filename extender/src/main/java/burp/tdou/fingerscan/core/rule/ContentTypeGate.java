package burp.tdou.fingerscan.core.rule;

import burp.tdou.common.utils.StringUtils;
import burp.tdou.fingerscan.common.Config;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 判断响应是否应进入 YAML 指纹正则匹配。
 * 空 body 由调用方最先短路，不经过本类。
 */
public final class ContentTypeGate {

    private static final String[] SKIP_EXACT_PREFIX = {
            "audio/", "video/", "font/", "application/font-"
    };

    private static final String[] SKIP_CONTAINS = {
            "application/pdf",
            "application/zip",
            "application/gzip",
            "application/x-gzip",
            "application/x-7z-compressed",
            "application/x-rar-compressed",
            "application/x-msdownload"
    };

    private ContentTypeGate() {
    }

    /**
     * @param contentType may be null
     * @param requestPath for octet-stream suffix check, may be null
     * @return true = 允许跑 YAML
     */
    public static boolean allowYamlMatch(String contentType, String requestPath) {
        if (!Config.getBoolean(Config.KEY_MATCH_SKIP_BINARY)) {
            return true;
        }
        if (contentType == null || contentType.isEmpty()) {
            return true;
        }
        String ct = contentType.toLowerCase(Locale.ROOT);
        // strip parameters
        int semi = ct.indexOf(';');
        if (semi >= 0) {
            ct = ct.substring(0, semi).trim();
        }

        if (ct.startsWith("image/")) {
            return ct.contains("svg"); // image/svg+xml
        }
        for (String p : SKIP_EXACT_PREFIX) {
            if (ct.startsWith(p)) {
                return false;
            }
        }
        for (String p : SKIP_CONTAINS) {
            if (ct.contains(p)) {
                return false;
            }
        }

        if (ct.equals("application/octet-stream") || ct.contains("application/octet-stream")) {
            return !pathHasSkipSuffix(requestPath);
        }

        // text/*, json, javascript, xml, xhtml → allow；其它未知偏 allow
        return true;
    }

    static boolean pathHasSkipSuffix(String requestPath) {
        if (requestPath == null || requestPath.isEmpty()) {
            return false;
        }
        String path = requestPath;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        int h = path.indexOf('#');
        if (h >= 0) {
            path = path.substring(0, h);
        }
        int slash = path.lastIndexOf('/');
        String file = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = file.lastIndexOf('.');
        if (dot < 0 || dot == file.length() - 1) {
            return false;
        }
        String ext = file.substring(dot + 1).toLowerCase(Locale.ROOT);
        return matchSkipSuffixes().contains(ext);
    }

    /**
     * 门禁用后缀 = KEY_EXCLUDE_SUFFIX ∪ {js, map, wasm}
     * 仅影响 YAML 匹配，不改变 SuffixFilter。
     */
    static Set<String> matchSkipSuffixes() {
        Set<String> set = new HashSet<>();
        String raw = Config.get(Config.KEY_EXCLUDE_SUFFIX);
        if (StringUtils.isNotEmpty(raw)) {
            for (String p : raw.toLowerCase(Locale.ROOT).split("\\|")) {
                String t = p.trim();
                if (!t.isEmpty()) {
                    set.add(t);
                }
            }
        }
        set.add("js");
        set.add("map");
        set.add("wasm");
        return Collections.unmodifiableSet(set);
    }
}
