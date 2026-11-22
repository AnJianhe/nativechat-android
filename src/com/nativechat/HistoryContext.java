package com.nativechat;

import org.json.JSONObject;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 只处理调用者提供的当前分支快照，不访问数据库、网络或其他分支。
 * createdAt 使用 Unix 毫秒。调用期间调用者不得修改列表或消息。
 * 页和字符限制按 UTF-16 code unit 计，不是 token 估算。
 */
public final class HistoryContext {
    private HistoryContext() {}

    private static final Pattern DATE = Pattern.compile(
        "(?<!\\d)(?:(\\d{4})年)?(\\d{1,2})月(\\d{1,2})[日号]?(?:\\s*(?:到|至|[-~～—])\\s*(?:(\\d{4})年)?(?:(\\d{1,2})月)?(\\d{1,2})[日号]?)?");
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    public static final int MAX_PAGE_CHARS = 200_000;
    public static final int MAX_PAGE_TURNS = 200;

    /** 本地日期闭区间，转换为毫秒时使用当地日界线，正确覆盖夏令时。 */
    public static final class DateRange {
        public final LocalDate start;
        public final LocalDate end;
        public final ZoneId zone;
        public DateRange(LocalDate start, LocalDate end, ZoneId zone) {
            if (start == null || end == null || zone == null || end.isBefore(start))
                throw new IllegalArgumentException("invalid date range");
            this.start = start;
            this.end = end;
            this.zone = zone;
        }
        public long startMillis() { return start.atStartOfDay(zone).toInstant().toEpochMilli(); }
        public long endExclusiveMillis() { return end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(); }
    }

    /**
     * 查找一个中文日期或范围；未发现返回 null，发现但不合法抛 IllegalArgumentException。
     * 省略起始年份时选择不晚于 today 的最近一次该月日；显式年份不作推断。
     * 省略终点月份时继承起点月份；省略终点年份且月日早于起点则跨到次年。
     * 支持例如 11月12日到13日、2025年12月31日至1月2日。
     * 不识别“昨天”“上周”等相对表达，也不进行自然语言意图判断。
     */
    public static DateRange parseDateRange(String text, ZoneId zone, LocalDate today) {
        if (text == null || zone == null || today == null) throw new IllegalArgumentException("null argument");
        Matcher m = DATE.matcher(text);
        if (!m.find()) return null;
        try {
            int month = Integer.parseInt(m.group(2)), day = Integer.parseInt(m.group(3));
            int year = m.group(1) == null ? today.getYear() : Integer.parseInt(m.group(1));
            LocalDate start;
            // 2月29日需向过去找最近的有效年份，而不是直接失败。
            if (m.group(1) == null) {
                start = null;
                for (int y = year; y >= year - 8 && y >= 1; y--) {
                    try {
                        LocalDate candidate = LocalDate.of(y, month, day);
                        if (!candidate.isAfter(today)) { start = candidate; break; }
                    } catch (DateTimeException ignored) { }
                }
                if (start == null) throw new IllegalArgumentException("invalid start date");
            } else start = LocalDate.of(year, month, day);
            LocalDate end = start;
            if (m.group(6) != null) {
                int endMonth = m.group(5) == null ? month : Integer.parseInt(m.group(5));
                int endDay = Integer.parseInt(m.group(6));
                int endYear = m.group(4) == null ? start.getYear() : Integer.parseInt(m.group(4));
                if (m.group(4) == null && (endMonth < month || (endMonth == month && endDay < day))) endYear++;
                end = LocalDate.of(endYear, endMonth, endDay);
            }
            return new DateRange(start, end, zone);
        } catch (DateTimeException | NumberFormatException ex) {
            throw new IllegalArgumentException("invalid date range", ex);
        }
    }

    public static DateRange parseDateRange(String text) {
        ZoneId zone = ZoneId.systemDefault();
        return parseDateRange(text, zone, LocalDate.now(zone));
    }

    /** 一个完整轮次的纯文本快照；附件、思考、工具块等不会被导出。 */
    public static final class Turn {
        public final int userIndex;
        public final long createdAt;
        public final String userText;
        public final String assistantText;
        private Turn(int userIndex, Msg user, String assistantText) {
            this.userIndex = userIndex;
            this.createdAt = user.createdAt;
            this.userText = user.content == null ? "" : user.content;
            this.assistantText = assistantText;
        }
    }

    /** nextCursor 为 -1 表示扫描结束，否则在同一不可变快照上继续；截断会显式报告。 */
    public static final class Page {
        public final List<Turn> turns;
        public final int nextCursor;
        public final int characters;
        public final int skippedOversizeTurns;
        public final boolean truncated;
        private Page(List<Turn> turns, int nextCursor, int characters, int skipped) {
            this.turns = Collections.unmodifiableList(turns);
            this.nextCursor = nextCursor;
            this.characters = characters;
            this.skippedOversizeTurns = skipped;
            this.truncated = nextCursor != -1 || skipped != 0;
        }
    }

    /** 使用精确的过去 24 小时 [now-24h, now]，now 是 Unix 毫秒。 */
    public static Page recent(List<Msg> branch, long now, int cursor, int maxTurns, int maxChars) {
        long start = now < Long.MIN_VALUE + DAY_MS ? Long.MIN_VALUE : now - DAY_MS;
        if (branch == null || cursor < 0 || cursor > branch.size() || maxTurns < 1
            || maxTurns > MAX_PAGE_TURNS || maxChars < 1 || maxChars > MAX_PAGE_CHARS)
            throw new IllegalArgumentException("invalid page arguments");
        // First page is cursor 0; select newest first, then restore conversation order.
        int i = (cursor == 0 ? branch.size() : cursor) - 1;
        List<Turn> turns = new ArrayList<>();
        int used = 0, skipped = 0;
        while (i >= 1) {
            Msg answer = branch.get(i), user = branch.get(i - 1);
            if (user == null || !user.isUser() || user.createdAt <= 0 || user.createdAt < start
                || user.createdAt > now || answer == null || !Msg.ASSISTANT.equals(answer.role)
                || !(Msg.DONE.equals(answer.status) || Msg.STOPPED.equals(answer.status))) { i--; continue; }
            String question = user.content == null ? "" : user.content;
            long size = question.length(); boolean hasAnswer = false;
            for (JSONObject block : answer.blocks) {
                if (block != null && "text".equals(block.optString("type"))) {
                    String value = block.optString("text", "");
                    if (!value.isEmpty()) hasAnswer = true;
                    size += value.length();
                    if (size > maxChars) break;
                }
            }
            if (size > maxChars || !hasAnswer) { skipped++; i -= 2; continue; }
            if (turns.size() >= maxTurns || size > maxChars - used) break;
            StringBuilder reply = new StringBuilder((int) (size - question.length()));
            for (JSONObject block : answer.blocks)
                if (block != null && "text".equals(block.optString("type"))) reply.append(block.optString("text", ""));
            turns.add(new Turn(i - 1, user, reply.toString()));
            used += (int) size;
            i -= 2;
        }
        Collections.reverse(turns);
        boolean remaining = false;
        for (int j = i; j >= 1; j--) {
            Msg candidate = branch.get(j - 1), answer = branch.get(j);
            if (candidate != null && candidate.isUser() && candidate.createdAt >= start
                && candidate.createdAt <= now && answer != null && Msg.ASSISTANT.equals(answer.role)
                && (Msg.DONE.equals(answer.status) || Msg.STOPPED.equals(answer.status))) {
                remaining = true; break;
            }
        }
        return new Page(turns, remaining ? i + 1 : -1, used, skipped);
    }

    /** 按 user 的本地日期选取完整轮次，包含结束日期的整天。 */
    public static Page range(List<Msg> branch, DateRange range, int cursor, int maxTurns, int maxChars) {
        if (range == null) throw new IllegalArgumentException("range is null");
        return select(branch, range.startMillis(), range.endExclusiveMillis(), false, cursor, maxTurns, maxChars);
    }

    private static Page select(List<Msg> branch, long start, long end, boolean inclusiveEnd,
                               int cursor, int maxTurns, int maxChars) {
        if (branch == null || cursor < 0 || cursor > branch.size() || maxTurns < 1 ||
            maxTurns > MAX_PAGE_TURNS || maxChars < 1 || maxChars > MAX_PAGE_CHARS)
            throw new IllegalArgumentException("invalid page arguments");
        List<Turn> turns = new ArrayList<>();
        int used = 0, skipped = 0, i = cursor;
        while (i < branch.size()) {
            Msg user = branch.get(i);
            if (user == null || !user.isUser() || user.createdAt <= 0 || user.createdAt < start ||
                (inclusiveEnd ? user.createdAt > end : user.createdAt >= end) || i + 1 >= branch.size()) { i++; continue; }
            Msg answer = branch.get(i + 1);
            if (answer == null || !Msg.ASSISTANT.equals(answer.role) ||
                !(Msg.DONE.equals(answer.status) || Msg.STOPPED.equals(answer.status))) { i++; continue; }
            String question = user.content == null ? "" : user.content;
            // Count before building: neither a long reply nor a single enormous turn is copied.
            long size = question.length();
            boolean hasAnswer = false;
            for (JSONObject block : answer.blocks) {
                if (block != null && "text".equals(block.optString("type"))) {
                    String s = block.optString("text", "");
                    if (!s.isEmpty()) hasAnswer = true;
                    size += s.length();
                    if (size > maxChars) break;
                }
            }
            if (size > maxChars) { skipped++; i += 2; continue; }
            if (!hasAnswer) { i += 2; continue; }
            if (turns.size() >= maxTurns || size > maxChars - used) break;
            StringBuilder reply = new StringBuilder((int) (size - question.length()));
            for (JSONObject block : answer.blocks) {
                if (block != null && "text".equals(block.optString("type"))) reply.append(block.optString("text", ""));
            }
            turns.add(new Turn(i, user, reply.toString()));
            used += (int) size;
            i += 2;
        }
        return new Page(turns, i < branch.size() ? i : -1, used, skipped);
    }
}
