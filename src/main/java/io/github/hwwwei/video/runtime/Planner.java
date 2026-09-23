package io.github.hwwwei.video.runtime;

import io.github.hwwwei.video.runtime.MockAgents.Segment;
import java.util.ArrayList;
import java.util.List;

/** Splits oversized transcript paragraphs; long videos get smaller evidence windows. */
public final class Planner {
    private Planner() {}

    public static List<Segment> split(List<Segment> source, long videoDurationMs) {
        int charLimit = videoDurationMs >= 30 * 60 * 1000L ? 800 : 1600;
        List<Segment> chunks = new ArrayList<>();
        for (Segment segment : source) {
            String content = segment.text().trim();
            if (content.length() <= charLimit) {
                chunks.add(segment);
                continue;
            }
            int start = 0;
            int index = 1;
            while (start < content.length()) {
                int end = Math.min(content.length(), start + charLimit);
                if (end < content.length()) {
                    int boundary = content.lastIndexOf(' ', end);
                    if (boundary > start + charLimit / 2) end = boundary;
                }
                String part = content.substring(start, end).trim();
                long chunkStart = segment.startMs() + (segment.endMs() - segment.startMs()) * start / content.length();
                long chunkEnd = end == content.length() ? segment.endMs()
                    : segment.startMs() + (segment.endMs() - segment.startMs()) * end / content.length();
                if (!part.isEmpty()) chunks.add(new Segment(segment.id() + "-" + index++, chunkStart, Math.max(chunkStart + 1, chunkEnd), part));
                start = end;
                while (start < content.length() && Character.isWhitespace(content.charAt(start))) start++;
            }
        }
        return List.copyOf(chunks);
    }
}
