package app.lexiflow.data;

import java.util.ArrayList;
import java.util.List;

/** Use indexed ranges, then choose the best match for each persistent sense. */
final class DictionarySearch {
    private DictionarySearch() {}
    record Query(String sql,String[] arguments) {}

    static Query ranked(String key,boolean reverse) {
        String end = SearchNormalization.prefixEnd(key);
        String range = "search_key>=?" + (end == null ? "" : " AND search_key<?");
        // Each priority is one ASCII digit. MIN chooses the priority and matching key together.
        String hits = reverse
                ? "SELECT sense_id,(CASE WHEN search_key=? THEN '0' ELSE '1' END)||search_key AS rank "
                    + "FROM translation WHERE " + range
                : "SELECT s.id AS sense_id,(CASE WHEN e.search_key=? THEN '0' ELSE '2' END)||e.search_key AS rank "
                    + "FROM entry e JOIN sense s ON s.entry_id=e.id WHERE e." + range
                    + " UNION ALL SELECT s.id,(CASE WHEN f.search_key=? THEN '1' ELSE '3' END)||f.search_key "
                    + "FROM form f JOIN sense s ON s.entry_id=f.entry_id WHERE f." + range;
        List<String> arguments = new ArrayList<>();
        for (int i = 0; i < (reverse ? 1 : 2); i++) {
            arguments.add(key);
            arguments.add(key);
            if (end != null) arguments.add(end);
        }
        String sql = "WITH hits AS (" + hits + "), grouped AS (SELECT sense_id,min(rank) AS best FROM hits GROUP BY sense_id),"
                + " selected AS (SELECT g.sense_id,g.best FROM grouped g JOIN sense s ON s.id=g.sense_id"
                + " ORDER BY g.best,s.entry_id,s.position,s.id LIMIT 50)"
                + " SELECT e.id,s.id,e.spelling,e.pos,s.position,"
                + "(SELECT t.wording FROM translation t WHERE t.sense_id=s.id "
                + (reverse ? "AND t.search_key=substr(g.best,2) " : "")
                + "ORDER BY t.search_key,t.wording,t.id LIMIT 1),"
                + (reverse ? "NULL" : "(SELECT min(f.spelling) FROM form f WHERE f.entry_id=e.id "
                   + "AND f.search_key=substr(g.best,2) AND substr(g.best,1,1) IN ('1','3'))")
                + " FROM selected g JOIN sense s ON s.id=g.sense_id"
                + " JOIN entry e ON e.id=s.entry_id ORDER BY g.best,e.id,s.position,s.id";
        return new Query(sql,arguments.toArray(new String[0]));
    }
}
