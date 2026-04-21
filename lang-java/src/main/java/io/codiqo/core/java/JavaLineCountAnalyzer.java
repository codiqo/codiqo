package io.codiqo.core.java;

import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.google.common.collect.Lists;

import lombok.Value;
import lombok.experimental.UtilityClass;
import net.sourceforge.pmd.lang.ast.impl.javacc.JavaccToken;
import net.sourceforge.pmd.lang.document.FileLocation;
import net.sourceforge.pmd.lang.document.TextDocument;
import net.sourceforge.pmd.lang.document.TextRegion;
import net.sourceforge.pmd.lang.java.ast.ASTCompilationUnit;
import net.sourceforge.pmd.lang.java.ast.ASTExecutableDeclaration;
import net.sourceforge.pmd.lang.java.ast.JavaComment;

@UtilityClass
public class JavaLineCountAnalyzer {
    public static LineCounts analyze(ASTExecutableDeclaration node) {
        ASTCompilationUnit root = node.getRoot();
        TextDocument doc = root.getTextDocument();
        JavaccToken firstNonComment = skipLeadingComments(node.getFirstToken(), node.getLastToken());
        FileLocation startLoc = firstNonComment.getReportLocation();
        FileLocation endLoc = node.getLastToken().getReportLocation();
        int nodeBeginLine = startLoc.getStartLine();
        int nodeBeginCol = startLoc.getStartColumn();
        int nodeEndLine = endLoc.getEndLine();
        int nodeEndCol = endLoc.getEndColumn();
        List<CommentSpan> spans = collectContainedComments(root, nodeBeginLine, nodeBeginCol, nodeEndLine, nodeEndCol);

        int codeLines = 0;
        int commentLines = 0;
        for (int line = nodeBeginLine; line <= nodeEndLine; line++) {
            int effStart = line == nodeBeginLine ? nodeBeginCol : 1;
            int effEnd = line == nodeEndLine ? nodeEndCol : Integer.MAX_VALUE;
            String lineText = readLine(doc, line);

            LineClassification classification = classifyLine(lineText, line, effStart, effEnd, spans);
            if (classification.hasCode) {
                codeLines++;
            }
            if (classification.hasComment) {
                commentLines++;
            }
        }

        return new LineCounts(codeLines, commentLines);
    }
    private static List<CommentSpan> collectContainedComments(ASTCompilationUnit root, int nodeBeginLine, int nodeBeginCol, int nodeEndLine, int nodeEndCol) {
        List<CommentSpan> toReturn = Lists.newArrayList();
        for (JavaComment comment : root.getComments()) {
            FileLocation loc = comment.getReportLocation();
            int cBeginLine = loc.getStartLine();
            int cBeginCol = loc.getStartColumn();
            int cEndLine = loc.getEndLine();
            int cEndCol = loc.getEndColumn();

            if (compare(cBeginLine, cBeginCol, nodeBeginLine, nodeBeginCol) < 0) {
                continue;
            }
            if (compare(cEndLine, cEndCol, nodeEndLine, nodeEndCol) > 0) {
                continue;
            }
            toReturn.add(new CommentSpan(cBeginLine, cBeginCol, cEndLine, cEndCol));
        }
        return toReturn;
    }
    private static LineClassification classifyLine(String lineText, int line, int effStart, int effEnd, List<CommentSpan> spans) {
        int lineLen = lineText.length();
        int clampedEnd = Math.min(effEnd, lineLen + 1);
        int clampedStart = Math.min(effStart, clampedEnd);

        boolean[] isCommentChar = new boolean[lineLen];
        boolean hasComment = false;
        for (CommentSpan span : spans) {
            if (line < span.startLine || line > span.endLine) {
                continue;
            }
            int spanColStart = line == span.startLine ? span.startCol : 1;
            int spanColEnd = line == span.endLine ? span.endCol : lineLen + 1;

            int overlapStart = Math.max(spanColStart, clampedStart);
            int overlapEnd = Math.min(spanColEnd, clampedEnd);
            if (overlapStart >= overlapEnd) {
                continue;
            }
            hasComment = true;
            int fromIdx = overlapStart - 1;
            int toIdx = Math.min(overlapEnd - 1, lineLen);
            for (int i = fromIdx; i < toIdx; i++) {
                isCommentChar[i] = true;
            }
        }

        boolean hasCode = false;
        int fromIdx = clampedStart - 1;
        int toIdx = Math.min(clampedEnd - 1, lineLen);
        for (int i = fromIdx; i < toIdx; i++) {
            if (!isCommentChar[i] && !Character.isWhitespace(lineText.charAt(i))) {
                hasCode = true;
                break;
            }
        }

        return new LineClassification(hasCode, hasComment);
    }
    private static String readLine(TextDocument doc, int line) {
        TextRegion region = doc.createLineRange(line, line);
        return StringUtils.chomp(doc.sliceOriginalText(region).toString());
    }
    private static JavaccToken skipLeadingComments(JavaccToken first, JavaccToken last) {
        JavaccToken current = first;
        while (current != last && JavaComment.isComment(current)) {
            current = current.getNext();
        }
        return current;
    }
    private static int compare(int lineA, int colA, int lineB, int colB) {
        if (lineA != lineB) {
            return Integer.compare(lineA, lineB);
        }
        return Integer.compare(colA, colB);
    }

    @Value
    public static class LineCounts {
        int codeLines;
        int commentLines;
    }

    @Value
    private static class CommentSpan {
        int startLine;
        int startCol;
        int endLine;
        int endCol;
    }

    @Value
    private static class LineClassification {
        boolean hasCode;
        boolean hasComment;
    }
}
