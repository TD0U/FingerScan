package burp.tdou.fingerscan.core.iconhash;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FaviconLinkExtractor {

    private static final Pattern LINK_TAG = Pattern.compile(
            "<link\\b[^>]*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern REL_ATTR = Pattern.compile(
            "\\brel\\s*=\\s*(?:[\"']([^\"']+)[\"']|(\\S+))",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern HREF_ATTR = Pattern.compile(
            "\\bhref\\s*=\\s*(?:[\"']([^\"']+)[\"']|(\\S+?)(?=[\\s>]))",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ICON_REL = Pattern.compile(
            "(?:^|\\s)(?:shortcut\\s+)?icon(?:\\s|$)|(?:^|\\s)apple-touch-icon(?:-precomposed)?(?:\\s|$)|(?:^|\\s)mask-icon(?:\\s|$)",
            Pattern.CASE_INSENSITIVE);

    public static class FaviconLink {
        public final String host;
        public final String path;

        public FaviconLink(String host, String path) {
            this.host = host;
            this.path = path;
        }
    }

    public static List<FaviconLink> extract(String html, String requestPath) {
        List<FaviconLink> result = new ArrayList<>();
        if (html == null || html.isEmpty()) {
            return result;
        }

        Matcher linkMatcher = LINK_TAG.matcher(html);
        while (linkMatcher.find()) {
            String tag = linkMatcher.group();

            Matcher relMatcher = REL_ATTR.matcher(tag);
            if (!relMatcher.find()) continue;

            String relValue = relMatcher.group(1) != null ? relMatcher.group(1) : relMatcher.group(2);
            if (relValue == null || !ICON_REL.matcher(relValue).find()) continue;

            Matcher hrefMatcher = HREF_ATTR.matcher(tag);
            if (!hrefMatcher.find()) continue;

            String href = (hrefMatcher.group(1) != null ? hrefMatcher.group(1) : hrefMatcher.group(2));
            if (href == null) continue;
            href = href.trim();
            if (href.isEmpty() || href.startsWith("data:")) continue;

            FaviconLink link = resolveHref(href, requestPath);
            if (link != null && !link.path.isEmpty()) {
                boolean dup = result.stream().anyMatch(
                        r -> r.path.equals(link.path) && eq(r.host, link.host));
                if (!dup) {
                    result.add(link);
                }
            }
        }
        return result;
    }

    private static FaviconLink resolveHref(String href, String requestPath) {
        // 去掉 fragment，保留 query（动态 favicon 接口常把参数放在 query 里）
        href = stripFragment(href);

        if (href.startsWith("//")) {
            try {
                URI uri = new URI("https:" + href);
                return new FaviconLink(uri.getHost(), pathWithQuery(uri));
            } catch (Exception e) {
                return null;
            }
        }

        if (href.startsWith("http://") || href.startsWith("https://")) {
            try {
                URI uri = new URI(href);
                return new FaviconLink(uri.getHost(), pathWithQuery(uri));
            } catch (Exception e) {
                return null;
            }
        }

        String raw;
        if (href.startsWith("/")) {
            raw = href;
        } else {
            String basePath = "/";
            if (requestPath != null && requestPath.contains("/")) {
                // base 只用路径部分，不含 query
                String base = stripQueryAndFragment(requestPath);
                basePath = base.substring(0, base.lastIndexOf('/') + 1);
            }
            raw = basePath + href;
        }
        return new FaviconLink(null, normalizePathKeepQuery(raw));
    }

    /** URI 的 path + query（若有） */
    private static String pathWithQuery(URI uri) {
        String path = uri.getPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        String query = uri.getRawQuery();
        if (query != null && !query.isEmpty()) {
            return path + "?" + query;
        }
        return path;
    }

    private static String stripFragment(String s) {
        if (s == null) return null;
        int h = s.indexOf('#');
        return h >= 0 ? s.substring(0, h) : s;
    }

    private static String stripQueryAndFragment(String s) {
        if (s == null) return null;
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        int h = s.indexOf('#');
        if (h >= 0) s = s.substring(0, h);
        return s;
    }

    /**
     * 规范化 path，保留 query。
     * 例：/a/../b/?x=1 → /b/?x=1
     */
    private static String normalizePathKeepQuery(String pathAndQuery) {
        if (pathAndQuery == null || pathAndQuery.isEmpty()) {
            return pathAndQuery;
        }
        String path = pathAndQuery;
        String query = null;
        int q = pathAndQuery.indexOf('?');
        if (q >= 0) {
            path = pathAndQuery.substring(0, q);
            query = pathAndQuery.substring(q + 1);
        }
        path = normalizePath(path);
        if (query != null && !query.isEmpty()) {
            return path + "?" + query;
        }
        return path;
    }

    private static String normalizePath(String path) {
        String[] segments = path.split("/", -1);
        List<String> normalized = new ArrayList<>();
        for (String seg : segments) {
            if (seg.equals(".")) {
                continue;
            } else if (seg.equals("..")) {
                if (!normalized.isEmpty()) {
                    normalized.remove(normalized.size() - 1);
                }
            } else {
                normalized.add(seg);
            }
        }
        String result = String.join("/", normalized);
        if (!result.startsWith("/")) {
            result = "/" + result;
        }
        return result;
    }

    private static boolean eq(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }
}
