import java.util.*;
// ConfPackages.parentsFirst/references/plainNumber copied verbatim (K32), timed on synthetic packages
public class Bench {
    record Statement(String text, String kind, String table, String schema, Map<String, String> values) {}
    static List<Statement> parentsFirst(List<Statement> inserts, Map<String, String> links) {
        List<Statement> left = new ArrayList<>(inserts);
        List<Statement> result = new ArrayList<>();
        while (!left.isEmpty()) {
            int next = 0;
            for (int i = 0; i < left.size(); i++) {
                Statement candidate = left.get(i);
                if (left.stream().noneMatch(other -> other != candidate && references(candidate, other, links))) { next = i; break; }
            }
            result.add(left.remove(next));
        }
        return result;
    }
    static boolean references(Statement child, Statement parent, Map<String, String> links) {
        for (Map.Entry<String, String> link : links.entrySet()) {
            int fkDot = link.getKey().lastIndexOf('.'); int refDot = link.getValue().lastIndexOf('.');
            if (child.table().equals(link.getKey().substring(0, fkDot)) && parent.table().equals(link.getValue().substring(0, refDot))) {
                String value = child.values().get(link.getKey().substring(fkDot + 1));
                String key = parent.values().get(link.getValue().substring(refDot + 1));
                if (value != null && key != null && plainNumber(value).equals(plainNumber(key))) return true;
            }
        }
        return false;
    }
    static String plainNumber(String v) { try { return new java.math.BigDecimal(v).stripTrailingZeros().toPlainString(); } catch (NumberFormatException e) { return v; } }
    // Proposed: same result (stable "first row whose package parents are already placed", cycle -> file order),
    // parents indexed by table.column=value so each row is visited once per link: O(n * links)
    static List<Statement> parentsFirstFast(List<Statement> inserts, Map<String, String> links) {
        int n = inserts.size();
        Map<String, List<Integer>> byKey = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Statement p = inserts.get(i);
            for (String ref : new HashSet<>(links.values())) {
                int dot = ref.lastIndexOf('.');
                String key = p.table().equals(ref.substring(0, dot)) ? p.values().get(ref.substring(dot + 1)) : null;
                if (key != null) byKey.computeIfAbsent(ref + "=" + plainNumber(key), k -> new ArrayList<>()).add(i);
            }
        }
        List<Set<Integer>> parents = new ArrayList<>(); List<List<Integer>> children = new ArrayList<>();
        for (int i = 0; i < n; i++) { parents.add(new HashSet<>()); children.add(new ArrayList<>()); }
        for (int i = 0; i < n; i++) {
            Statement c = inserts.get(i);
            for (Map.Entry<String, String> link : links.entrySet()) {
                int dot = link.getKey().lastIndexOf('.');
                String v = c.table().equals(link.getKey().substring(0, dot)) ? c.values().get(link.getKey().substring(dot + 1)) : null;
                if (v == null) continue;
                for (int p : byKey.getOrDefault(link.getValue() + "=" + plainNumber(v), List.of())) if (p != i && parents.get(i).add(p)) children.get(p).add(i);
            }
        }
        int[] deg = new int[n]; TreeSet<Integer> left = new TreeSet<>(), free = new TreeSet<>();
        for (int i = 0; i < n; i++) { deg[i] = parents.get(i).size(); left.add(i); if (deg[i] == 0) free.add(i); }
        List<Statement> result = new ArrayList<>(n);
        while (!left.isEmpty()) {
            int next = free.isEmpty() ? left.first() : free.first();
            free.remove(next); left.remove(next); result.add(inserts.get(next));
            for (int ch : children.get(next)) if (left.contains(ch) && --deg[ch] == 0) free.add(ch);
        }
        return result;
    }

    public static void main(String[] a) {
        Random r = new Random(1);
        for (int n : new int[]{50, 200, 500, 2000, 5000}) {
            Map<String, String> links = new HashMap<>();
            for (int t = 1; t <= 20; t++) { links.put("entity_" + t + ".status_id", "dict_3.id"); links.put("entity_" + t + ".prev_status_id", "dict_3.id"); links.put("entity_" + t + ".type_id", "dict_1.id"); }
            List<Statement> st = new ArrayList<>();
            int nd = Math.max(2, n / 10);
            // children first in file (worst realistic case: user adds child rows then the parent rows)
            for (int i = 0; i < n - nd; i++) { String s = "" + (9000 + r.nextInt(nd)); st.add(new Statement("", "INSERT", "entity_" + (1 + i % 20), "", Map.of("id", "" + (900000 + i), "status_id", s, "prev_status_id", s, "type_id", "" + (1 + i % 5)))); }
            for (int i = 0; i < nd; i++) st.add(new Statement("", "INSERT", "dict_3", "", Map.of("id", "" + (9000 + i))));
            long t0 = System.nanoTime(); List<Statement> fast = parentsFirstFast(st, links); long fms = (System.nanoTime() - t0) / 1_000_000;
            String old = "-";
            if (n <= 500) { t0 = System.nanoTime(); List<Statement> slow = parentsFirst(st, links); old = (System.nanoTime() - t0) / 1_000_000 + " ms";
                if (!slow.equals(fast)) throw new AssertionError("order differs at n=" + n); }
            System.out.printf("n=%5d  mevcut %10s   onerilen %5d ms%n", n, old, fms);
        }
    }
}
