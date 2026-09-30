// The judge runs complete programs from stdin. Each starter keeps the interview
// method separate from a small input/output adapter, so candidates can focus on
// the same method signature they would see in a coding interview.
const descriptions = {
  'two-sum': 'Find two different elements in nums whose values add up to target. Return their zero-based indices in increasing order, or -1 if none exist. In the sample, "4 9" means four numbers and target 9; the next line is nums = [2, 7, 11, 15]. Since 2 + 7 = 9, print 0 1.',
  'valid-parentheses': 'Decide whether every opening bracket is closed by the matching type in the correct order. For example, "([])" is valid, while "([)]" is not.',
  'merge-intervals': 'Combine intervals that overlap or touch. For example, [[1, 3], [2, 6], [8, 10]] becomes [[1, 6], [8, 10]].',
  'longest-substring': 'Find the length of the longest contiguous part of the string with no repeated character. For "abcabcbb", the answer is 3.',
  'course-schedule': 'Each pair [course, prerequisite] means the prerequisite must be finished first. Decide whether all courses can be completed.',
  'kth-largest': 'Return the element in position k after sorting in descending order. Repeated values count as separate elements.',
  'word-ladder': 'Change one letter at a time from beginWord to endWord, using only words in the dictionary. Return the number of words in the shortest path, or 0.',
  'median-stream': 'After each new number, return the median of all numbers seen so far. For an even count, average the two middle values.',
  'edit-distance': 'Find the fewest single-character insertions, deletions, or replacements needed to change the first string into the second.'
}

const python = {
  'two-sum': String.raw`class Solution:
    def twoSum(self, nums: list[int], target: int) -> list[int]:
        # TODO: Return [i, j] (i < j), or [-1] when no pair exists.
        return [-1]


# The judge uses stdin/stdout; solve only the method above.
if __name__ == "__main__":
    n, target = map(int, input().split())
    nums = list(map(int, input().split()))
    print(*Solution().twoSum(nums[:n], target))
`,
  'valid-parentheses': String.raw`class Solution:
    def isValid(self, s: str) -> bool:
        # TODO: Check whether the brackets are properly nested.
        return False


if __name__ == "__main__":
    import sys
    s = sys.stdin.readline().rstrip("\n")
    print(str(Solution().isValid(s)).lower())
`,
  'merge-intervals': String.raw`class Solution:
    def merge(self, intervals: list[list[int]]) -> list[list[int]]:
        # TODO: Return merged intervals in ascending order.
        return []


if __name__ == "__main__":
    import sys
    data = list(map(int, sys.stdin.buffer.read().split()))
    n = data[0]
    intervals = [[data[i], data[i + 1]] for i in range(1, 2 * n + 1, 2)]
    for start, end in Solution().merge(intervals):
        print(start, end)
`,
  'longest-substring': String.raw`class Solution:
    def lengthOfLongestSubstring(self, s: str) -> int:
        # TODO: Return the longest length with no repeated character.
        return 0


if __name__ == "__main__":
    import sys
    print(Solution().lengthOfLongestSubstring(sys.stdin.readline().rstrip("\n")))
`,
  'course-schedule': String.raw`class Solution:
    def canFinish(self, numCourses: int, prerequisites: list[list[int]]) -> bool:
        # TODO: Return whether all courses can be completed.
        return False


if __name__ == "__main__":
    import sys
    data = list(map(int, sys.stdin.buffer.read().split()))
    n, m = data[:2]
    prerequisites = [[data[i], data[i + 1]] for i in range(2, 2 + 2 * m, 2)]
    print(str(Solution().canFinish(n, prerequisites)).lower())
`,
  'kth-largest': String.raw`class Solution:
    def findKthLargest(self, nums: list[int], k: int) -> int:
        # TODO: Return the kth largest element, counting duplicates.
        return 0


if __name__ == "__main__":
    import sys
    data = list(map(int, sys.stdin.buffer.read().split()))
    n, k = data[:2]
    print(Solution().findKthLargest(data[2:2 + n], k))
`,
  'word-ladder': String.raw`class Solution:
    def ladderLength(self, beginWord: str, endWord: str, wordList: list[str]) -> int:
        # TODO: Return the shortest transformation length, or 0.
        return 0


if __name__ == "__main__":
    import sys
    lines = sys.stdin.read().splitlines()
    n = int(lines[2])
    print(Solution().ladderLength(lines[0], lines[1], lines[3:3 + n]))
`,
  'median-stream': String.raw`class MedianFinder:
    def addNum(self, num: int) -> None:
        # TODO: Add num to the stream.
        pass

    def findMedian(self) -> float:
        # TODO: Return the median of all numbers added so far.
        return 0.0


if __name__ == "__main__":
    import sys
    data = list(map(int, sys.stdin.buffer.read().split()))
    finder = MedianFinder()
    for num in data[1:1 + data[0]]:
        finder.addNum(num)
        print(f"{finder.findMedian():.1f}")
`,
  'edit-distance': String.raw`class Solution:
    def minDistance(self, word1: str, word2: str) -> int:
        # TODO: Return the minimum edit distance.
        return 0


if __name__ == "__main__":
    import sys
    lines = sys.stdin.read().splitlines()
    print(Solution().minDistance(lines[0] if lines else "", lines[1] if len(lines) > 1 else ""))
`
}

const java = {
  'two-sum': String.raw`import java.util.*;

class Solution {
    public int[] twoSum(int[] nums, int target) {
        // TODO: Return {i, j} (i < j), or {-1} when no pair exists.
        return new int[]{-1};
    }
}

// The judge uses stdin/stdout; solve only the method above.
public class Main {
    public static void main(String[] args) {
        Scanner in = new Scanner(System.in);
        int n = in.nextInt(), target = in.nextInt();
        int[] nums = new int[n];
        for (int i = 0; i < n; i++) nums[i] = in.nextInt();
        int[] answer = new Solution().twoSum(nums, target);
        if (answer.length == 1 && answer[0] == -1) System.out.println(-1);
        else System.out.println(answer[0] + " " + answer[1]);
    }
}
`,
  'valid-parentheses': String.raw`import java.io.*;

class Solution {
    public boolean isValid(String s) {
        // TODO: Check whether the brackets are properly nested.
        return false;
    }
}

public class Main {
    public static void main(String[] args) throws Exception {
        String s = new BufferedReader(new InputStreamReader(System.in)).readLine();
        System.out.println(new Solution().isValid(s == null ? "" : s));
    }
}
`,
  'merge-intervals': String.raw`import java.util.*;

class Solution {
    public int[][] merge(int[][] intervals) {
        // TODO: Return merged intervals in ascending order.
        return new int[0][2];
    }
}

public class Main {
    public static void main(String[] args) {
        Scanner in = new Scanner(System.in);
        int n = in.nextInt();
        int[][] intervals = new int[n][2];
        for (int i = 0; i < n; i++) {
            intervals[i][0] = in.nextInt();
            intervals[i][1] = in.nextInt();
        }
        for (int[] interval : new Solution().merge(intervals))
            System.out.println(interval[0] + " " + interval[1]);
    }
}
`,
  'longest-substring': String.raw`import java.io.*;

class Solution {
    public int lengthOfLongestSubstring(String s) {
        // TODO: Return the longest length with no repeated character.
        return 0;
    }
}

public class Main {
    public static void main(String[] args) throws Exception {
        String s = new BufferedReader(new InputStreamReader(System.in)).readLine();
        System.out.println(new Solution().lengthOfLongestSubstring(s == null ? "" : s));
    }
}
`,
  'course-schedule': String.raw`import java.util.*;

class Solution {
    public boolean canFinish(int numCourses, int[][] prerequisites) {
        // TODO: Return whether all courses can be completed.
        return false;
    }
}

public class Main {
    public static void main(String[] args) {
        Scanner in = new Scanner(System.in);
        int n = in.nextInt(), m = in.nextInt();
        int[][] prerequisites = new int[m][2];
        for (int i = 0; i < m; i++) {
            prerequisites[i][0] = in.nextInt();
            prerequisites[i][1] = in.nextInt();
        }
        System.out.println(new Solution().canFinish(n, prerequisites));
    }
}
`,
  'kth-largest': String.raw`import java.util.*;

class Solution {
    public int findKthLargest(int[] nums, int k) {
        // TODO: Return the kth largest element, counting duplicates.
        return 0;
    }
}

public class Main {
    public static void main(String[] args) {
        Scanner in = new Scanner(System.in);
        int n = in.nextInt(), k = in.nextInt();
        int[] nums = new int[n];
        for (int i = 0; i < n; i++) nums[i] = in.nextInt();
        System.out.println(new Solution().findKthLargest(nums, k));
    }
}
`,
  'word-ladder': String.raw`import java.io.*;
import java.util.*;

class Solution {
    public int ladderLength(String beginWord, String endWord, List<String> wordList) {
        // TODO: Return the shortest transformation length, or 0.
        return 0;
    }
}

public class Main {
    public static void main(String[] args) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String begin = in.readLine(), end = in.readLine();
        int n = Integer.parseInt(in.readLine());
        List<String> words = new ArrayList<>();
        for (int i = 0; i < n; i++) words.add(in.readLine());
        System.out.println(new Solution().ladderLength(begin, end, words));
    }
}
`,
  'median-stream': String.raw`import java.util.*;

class MedianFinder {
    public void addNum(int num) {
        // TODO: Add num to the stream.
    }

    public double findMedian() {
        // TODO: Return the median of all numbers added so far.
        return 0.0;
    }
}

public class Main {
    public static void main(String[] args) {
        Scanner in = new Scanner(System.in);
        MedianFinder finder = new MedianFinder();
        int n = in.nextInt();
        for (int i = 0; i < n; i++) {
            finder.addNum(in.nextInt());
            System.out.printf(Locale.ROOT, "%.1f%n", finder.findMedian());
        }
    }
}
`,
  'edit-distance': String.raw`import java.io.*;

class Solution {
    public int minDistance(String word1, String word2) {
        // TODO: Return the minimum edit distance.
        return 0;
    }
}

public class Main {
    public static void main(String[] args) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String first = in.readLine(), second = in.readLine();
        System.out.println(new Solution().minDistance(first == null ? "" : first, second == null ? "" : second));
    }
}
`
}

export function descriptionFor(problemId) {
  return descriptions[problemId] || ''
}

export function templateFor(problemId, language) {
  return (language === 'java' ? java : python)[problemId] || ''
}
