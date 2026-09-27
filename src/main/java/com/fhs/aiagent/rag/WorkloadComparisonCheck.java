package com.fhs.aiagent.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Structural comparability only: no assertion about numeric truth, fairness or feasibility. */
final class WorkloadComparisonCheck {
    private static final List<Pattern> METRICS = List.of(
            Pattern.compile("学习|备考"), Pattern.compile("夜醒|夜间|夜班"), Pattern.compile("家务|照护|育儿"));
    private static final Pattern CELL = Pattern.compile(
            "^(?:\\d+(?:\\.\\d+)?|待填|待记录)\\s*(?:小时|分钟|次数|晚数|次|晚)?(?:/(?:天|日|周|月))?\\s*(?:（[^（）]{0,100}）|\\([^()]{0,100}\\))?$");
    private static final Pattern UNIT = Pattern.compile("小时|分钟|次数|晚数|次|晚");
    private static final Pattern PERIOD = Pattern.compile("(?:/|每|按)(天|日|周|月)");

    static boolean passed(String answer) {
        if (!answer.matches("(?s).*(?:如果|若|当|超过|高于|低于).*(?:调整|轮换|补偿|重排).*")) return false;
        // Check complete tables, not a prefix which might hide contradictory later rows.
        for (String block : answer.split("(?:\\r?\\n)[ \\t]*(?:\\r?\\n)")) {
            List<String[]> rows = new ArrayList<>();
            boolean header = false;
            for (String line : block.split("\\R")) {
                String[] cells = line.strip().split("\\|", -1);
                if (cells.length == 5 && cells[0].isBlank() && cells[4].isBlank()) {
                    if (cells[1].trim().matches("指标|项目|负担指标")
                            && cells[2].trim().matches("A|甲|我|本人") && cells[3].trim().matches("B|乙|伴侣|对方")) {
                        if (header && tablePassed(rows)) return true;
                        rows.clear(); header = true;
                    } else if (header) rows.add(cells);
                } else if (header) {
                    if (tablePassed(rows)) return true;
                    rows.clear(); header = false;
                }
            }
            if (header && tablePassed(rows)) return true;
        }
        return false;
    }

    private static boolean tablePassed(List<String[]> rows) {
        String tablePeriod = "";
        for (var row : rows) if (row[1].trim().equals("统计周期")) {
            String left = metadata(row[2], PERIOD, true), right = metadata(row[3], PERIOD, true);
            if (left.isEmpty() || left.equals("INVALID") || !left.equals(right)
                    || (!tablePeriod.isEmpty() && !tablePeriod.equals(left))) return false;
            tablePeriod = left;
        }
        boolean[] present = new boolean[3];
        for (var row : rows) {
            int metric = -1;
            for (int i = 0; i < METRICS.size(); i++) if (METRICS.get(i).matcher(row[1]).find()) {
                if (metric == 1 && i == 2 && !row[1].contains("家务")) continue; // 夜间照护 is one night metric.
                if (metric != -1) return false; // A keyword-stuffed row is not three measurements.
                metric = i;
            }
            if (metric == -1) continue;
            if (present[metric]) return false;
            if (!CELL.matcher(row[2].trim()).matches() || !CELL.matcher(row[3].trim()).matches()) return false;
            String baseUnit = metadata(row[1], UNIT, false);
            String basePeriod = merge(tablePeriod, metadata(row[1], PERIOD, true));
            String leftUnit = merge(baseUnit, metadata(row[2], UNIT, false));
            String rightUnit = merge(baseUnit, metadata(row[3], UNIT, false));
            String leftPeriod = merge(basePeriod, metadata(row[2], PERIOD, true));
            String rightPeriod = merge(basePeriod, metadata(row[3], PERIOD, true));
            if (!sameKnown(leftUnit, rightUnit) || !sameKnown(leftPeriod, rightPeriod)) return false;
            present[metric] = true;
        }
        return present[0] && present[1] && present[2];
    }

    private static boolean sameKnown(String a, String b) {
        return !a.isEmpty() && !a.equals("INVALID") && a.equals(b);
    }

    private static String merge(String a, String b) {
        return a.isEmpty() ? b : b.isEmpty() || a.equals(b) ? a : "INVALID";
    }

    private static String metadata(String text, Pattern pattern, boolean period) {
        var matches = pattern.matcher(text);
        String result = "";
        while (matches.find()) {
            String value = period ? matches.group(1).replace("日", "天") : matches.group().replace("次数", "次").replace("晚数", "晚");
            result = merge(result, value);
        }
        return result;
    }
}
