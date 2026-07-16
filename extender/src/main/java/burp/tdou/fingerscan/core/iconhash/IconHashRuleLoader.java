package burp.tdou.fingerscan.core.iconhash;

import burp.tdou.common.log.Logger;
import burp.tdou.fingerscan.config.YamlConfigStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class IconHashRuleLoader {

    private final YamlConfigStore configStore;
    private Map<String, List<IconHashRule>> murmurIndex = new HashMap<>();
    private Map<String, List<IconHashRule>> md5Index = new HashMap<>();
    private long lastLoadTime = 0;

    public IconHashRuleLoader(YamlConfigStore configStore) {
        this.configStore = configStore;
    }

    @SuppressWarnings("unchecked")
    public void loadRules() {
        Map<String, Object> config = configStore.readConfig();
        if (config == null) {
            return;
        }

        Object obj = config.get("Icon_Hash_List");
        if (!(obj instanceof List)) {
            return;
        }

        Map<String, List<IconHashRule>> newMurmurIndex = new HashMap<>();
        Map<String, List<IconHashRule>> newMd5Index = new HashMap<>();

        List<Map<String, Object>> ruleList = (List<Map<String, Object>>) obj;
        for (Map<String, Object> entry : ruleList) {
            try {
                String name = getStr(entry, "name");
                String murmurHash = getStr(entry, "murmur_hash");
                String md5 = getStr(entry, "md5");
                String type = getStr(entry, "type");
                String info = getStr(entry, "info");

                if (name == null || name.isEmpty()) {
                    continue;
                }

                IconHashRule rule = new IconHashRule(name, murmurHash, md5, type, info);

                if (rule.hasMurmurHash()) {
                    newMurmurIndex.computeIfAbsent(murmurHash, k -> new ArrayList<>()).add(rule);
                }
                if (rule.hasMd5()) {
                    newMd5Index.computeIfAbsent(md5, k -> new ArrayList<>()).add(rule);
                }
            } catch (Exception e) {
                Logger.debug("IconHashRuleLoader: skip invalid rule: %s", e.getMessage());
            }
        }

        this.murmurIndex = newMurmurIndex;
        this.md5Index = newMd5Index;
        this.lastLoadTime = System.currentTimeMillis();
        Logger.debug("IconHashRuleLoader: loaded %d murmur rules, %d md5 rules",
                newMurmurIndex.size(), newMd5Index.size());
    }

    public List<IconHashRule> findByMurmurHash(String hash) {
        ensureLoaded();
        List<IconHashRule> rules = murmurIndex.get(hash);
        return rules != null ? rules : new ArrayList<>();
    }

    public List<IconHashRule> findByMd5(String md5) {
        ensureLoaded();
        List<IconHashRule> rules = md5Index.get(md5);
        return rules != null ? rules : new ArrayList<>();
    }

    public int getRuleCount() {
        ensureLoaded();
        return murmurIndex.size() + md5Index.size();
    }

    public void invalidate() {
        lastLoadTime = 0;
    }

    private void ensureLoaded() {
        if (lastLoadTime == 0) {
            loadRules();
        }
    }

    private String getStr(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : null;
    }
}
