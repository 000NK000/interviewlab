package dev.interviewlab;

import java.util.*;

/** Curated stdin/stdout contracts. Hidden cases stay on the judge side. */
final class ProblemBank {
    record Spec(String id,String title,String prompt,List<RoomApi.TestCase> samples,List<RoomApi.TestCase> hidden) {}
    private static RoomApi.TestCase c(String input,String expected) { return new RoomApi.TestCase(input,expected); }
    private static final Map<String,Spec> SPECS=new LinkedHashMap<>();
    static {
        add("two-sum","Two Sum","Input: line 1 has n and target; line 2 has n integers. Print two distinct zero-based indices in ascending order, or -1 when absent. The tests have at most one valid pair.",
            List.of(c("4 9\n2 7 11 15\n","0 1")),List.of(c("3 6\n3 2 4\n","1 2"),c("3 10\n1 2 3\n","-1")));
        add("valid-parentheses","Valid Parentheses","Input: one line of ()[]{} characters, possibly empty. Print lowercase true if the symbols are properly nested, otherwise false.",
            List.of(c("([]){}\n","true")),List.of(c("([)]\n","false"),c("\n","true")));
        add("merge-intervals","Merge Intervals","Input: line 1 has n; the next n lines each contain start end. Merge overlapping or touching closed intervals. Print one merged interval per line in ascending order; print nothing for n=0.",
            List.of(c("4\n1 3\n2 6\n8 10\n15 18\n","1 6\n8 10\n15 18")),List.of(c("3\n5 7\n1 2\n2 3\n","1 3\n5 7"),c("0\n","")));
        add("longest-substring","Longest Unique Substring","Input: one line of ASCII text, possibly empty. Print the length of the longest substring without a repeated character.",
            List.of(c("abcabcbb\n","3")),List.of(c("bbbbb\n","1"),c("\n","0")));
        add("course-schedule","Course Schedule","Input: line 1 has number of courses n and number of prerequisite pairs m. Each following line is course prerequisite. Print lowercase true if all courses can be completed, otherwise false.",
            List.of(c("2 1\n1 0\n","true")),List.of(c("2 2\n1 0\n0 1\n","false"),c("3 0\n","true")));
        add("kth-largest","Kth Largest Element","Input: line 1 has n and k; line 2 has n integers. Print the kth largest element, counting duplicates.",
            List.of(c("6 2\n3 2 1 5 6 4\n","5")),List.of(c("6 4\n3 2 3 1 2 4\n","2"),c("1 1\n-5\n","-5")));
        add("word-ladder","Word Ladder","Input: line 1 begin word, line 2 end word, line 3 dictionary size n, then n dictionary words. Print shortest transformation length including begin and end, or 0 if unreachable. Words use lowercase ASCII and have equal length.",
            List.of(c("hit\ncog\n6\nhot\ndot\ndog\nlot\nlog\ncog\n","5")),List.of(c("hit\ncog\n5\nhot\ndot\ndog\nlot\nlog\n","0"),c("a\nc\n2\nb\nc\n","2")));
        add("median-stream","Median from Data Stream","Input: line 1 has n, followed by n integers (one per line). After each insertion print the median on its own line, formatted with exactly one digit after the decimal point.",
            List.of(c("3\n1\n2\n3\n","1.0\n1.5\n2.0")),List.of(c("4\n-1\n-2\n-3\n-4\n","-1.0\n-1.5\n-2.0\n-2.5")));
        add("edit-distance","Edit Distance","Input: two lines of ASCII text; either may be empty. Print the minimum number of insertions, deletions or replacements needed to transform the first line into the second.",
            List.of(c("horse\nros\n","3")),List.of(c("intention\nexecution\n","5"),c("\nabc\n","3")));
    }
    private static void add(String id,String title,String prompt,List<RoomApi.TestCase> samples,List<RoomApi.TestCase> hidden) {
        SPECS.put(id,new Spec(id,title,prompt,List.copyOf(samples),List.copyOf(hidden)));
    }
    static Spec get(String id) { return id==null?null:SPECS.get(id); }
    static List<RoomApi.TestCase> samples(String id) { Spec spec=get(id);return spec==null?List.of():spec.samples; }
    static List<RoomApi.TestCase> hidden(String id) { Spec spec=get(id);return spec==null?List.of():spec.hidden; }
    static List<Map<String,Object>> publicSpecs() { return SPECS.values().stream().map(s->Map.<String,Object>of("id",s.id,"title",s.title,"prompt",s.prompt,"sampleCases",s.samples)).toList(); }
    private ProblemBank() {}
}
