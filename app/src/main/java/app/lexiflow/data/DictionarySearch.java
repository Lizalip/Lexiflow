package app.lexiflow.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Use indexed ranges, then choose the best match for each persistent sense. */
final class DictionarySearch {
    private DictionarySearch() {}
    record Query(String sql,String[] arguments) {}

    static Query ranked(String key,boolean reverse) {
        return ranked(key,reverse,false);
    }

    static Query ranked(String key,boolean reverse,boolean bilingual) {
        String end = SearchNormalization.prefixEnd(key);
        String translations = range("search_key",end);
        String entries = range("e.search_key",end);
        String forms = range("f.search_key",end);
        // Each priority is one ASCII digit. MIN chooses the priority and matching key together.
        String hits = reverse
                ? "SELECT sense_id,(CASE WHEN search_key=? THEN '" + (bilingual ? "1' ELSE '4" : "0' ELSE '1")
                    + "' END)||search_key AS rank FROM translation WHERE " + translations + (bilingual ? " AND language='ru'" : "")
                : "SELECT s.id AS sense_id,(CASE WHEN e.search_key=? THEN '0' ELSE '2' END)||e.search_key AS rank "
                    + "FROM entry e JOIN sense s ON s.entry_id=e.id WHERE " + entries
                    + (bilingual ? " AND e.language='en'" : "")
                    + " UNION ALL SELECT s.id,(CASE WHEN f.search_key=? THEN '1' ELSE '3' END)||f.search_key "
                    + "FROM form f JOIN sense s ON s.entry_id=f.entry_id "
                    + (bilingual ? "JOIN entry e ON e.id=s.entry_id AND e.language='en' " : "")+"WHERE " + forms;
        if (reverse && bilingual) {
            hits += " UNION ALL SELECT s.id,(CASE WHEN e.search_key=? THEN '0' ELSE '3' END)||e.search_key "
                    + "FROM entry e JOIN sense s ON s.entry_id=e.id WHERE e.language='ru' AND " + entries;
        }
        List<String> arguments = new ArrayList<>();
        for (int i = 0; i < (reverse && !bilingual ? 1 : 2); i++) {
            arguments.add(key);
            arguments.add(key);
            if (end != null) arguments.add(end);
        }
        if (reverse && bilingual) {
            var variants = stressVariants(key);
            if (!variants.isEmpty()) {
                hits += " UNION ALL SELECT sense_id,'2'||search_key FROM translation"
                        +" WHERE language='ru' AND search_key IN ("+slots(variants.size())+")";
                arguments.addAll(variants);
            }
        }
        return selectHits(hits,reverse,arguments,bilingual);
    }

    private static String range(String column,String end) {
        return column+">=?" + (end==null ? "" : " AND "+column+"<?");
    }

    static Query russianStressFallback(String key) {
        var variants = stressVariants(key);
        if (variants.isEmpty()) return null;
        String hits = "SELECT sense_id,'0'||search_key AS rank FROM translation WHERE search_key IN ("
                +slots(variants.size())+")";
        return selectHits(hits,true,variants,false);
    }

    private static List<String> stressVariants(String key) {
        // A bounded set of exact indexed alternatives for a single unstressed Russian word.
        if (!key.matches("[а-яё]{1,32}")) return Collections.emptyList();
        List<String> variants = new ArrayList<>();
        for (int i=0;i<key.length();i++) {
            if ("аеёиоуыэюя".indexOf(key.charAt(i))>=0) {
                variants.add(key.substring(0,i+1)+"\u0301"+key.substring(i+1));
            }
        }
        return variants;
    }

    private static String slots(int count) {
        return String.join(",",Collections.nCopies(count,"?"));
    }

    private static Query selectHits(String hits,boolean reverse,List<String> arguments,boolean bilingual) {
        String sql = "WITH hits AS (" + hits + "), grouped AS (SELECT sense_id,min(rank) AS best FROM hits GROUP BY sense_id),"
                + " selected AS (SELECT g.sense_id,g.best FROM grouped g JOIN sense s ON s.id=g.sense_id"
                + " ORDER BY g.best,s.entry_id,s.position,s.id LIMIT 50)"
                + " SELECT e.id,s.id,e.spelling,e.pos,s.position,"
                + "(SELECT t.wording FROM translation t WHERE t.sense_id=s.id "
                + (reverse ? (bilingual ? "AND (e.language='ru' OR t.search_key=substr(g.best,2)) "
                                     : "AND t.search_key=substr(g.best,2) ") : "")
                + "ORDER BY t.search_key,t.wording,t.id LIMIT 1),"
                + (reverse ? "NULL" : "(SELECT min(f.spelling) FROM form f WHERE f.entry_id=e.id "
                   + "AND f.search_key=substr(g.best,2) AND substr(g.best,1,1) IN ('1','3'))")
                + (bilingual ? ",e.language" : ",'en'")
                + (bilingual && reverse ? ",substr(g.best,1,1)='2'" : ",0")
                + ",(SELECT group_concat(wording,char(31)) FROM (SELECT DISTINCT t.wording FROM translation t"
                + " WHERE t.sense_id=s.id ORDER BY t.id LIMIT 3))"
                + " FROM selected g JOIN sense s ON s.id=g.sense_id"
                + " JOIN entry e ON e.id=s.entry_id ORDER BY g.best,e.id,s.position,s.id";
        return new Query(sql,arguments.toArray(new String[0]));
    }
}
