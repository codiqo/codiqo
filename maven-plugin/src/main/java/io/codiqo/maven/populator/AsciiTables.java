package io.codiqo.maven.populator;

import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.github.freva.asciitable.AsciiTable;
import com.github.freva.asciitable.Column;
import com.github.freva.asciitable.ColumnData;
import com.github.freva.asciitable.HorizontalAlign;
import com.google.common.collect.Lists;

/**
 * ascii-table for the TEXT templates, which reach it as {@code #tables} (see {@link TextLayoutDialect}): the template
 * names the columns, their alignment and the value of each cell, the library draws the table. Each row is the list of
 * its cells, in the order of the headers. A header is any value: OGNL reads a one-letter literal such as {@code '+'}
 * as a character.
 */
public class AsciiTables {
    /** ascii-table's own padding: one space each side of the text plus the border */
    private static final int CELL_PADDING = 3;

    /** no line between the data rows */
    public String compact(List<?> headers, List<String> aligns, List<List<?>> rows) {
        return AsciiTable.getTable(AsciiTable.BASIC_ASCII_NO_DATA_SEPARATORS, rows, columns(headers, aligns, rows, List.of()));
    }
    /** a line between every two rows */
    public String grid(List<?> headers, List<String> aligns, List<List<?>> rows) {
        return grid(headers, aligns, rows, List.of());
    }
    /** {@code unwrapped} names the columns as wide as their longest cell, so a file or block name is never broken */
    public String grid(List<?> headers, List<String> aligns, List<List<?>> rows, List<String> unwrapped) {
        return AsciiTable.getTable(rows, columns(headers, aligns, rows, unwrapped));
    }
    private static List<ColumnData<List<?>>> columns(List<?> headers, List<String> aligns, List<List<?>> rows, List<String> unwrapped) {
        List<ColumnData<List<?>>> toReturn = Lists.newArrayList();
        for (int index = 0; index < headers.size(); index++) {
            int column = index;
            String header = String.valueOf(headers.get(column));
            Column definition = new Column().header(header).dataAlign(HorizontalAlign.valueOf(aligns.get(column)));
            if (unwrapped.contains(header)) {
                int longest = header.length();
                for (List<?> row : rows) {
                    longest = Math.max(longest, StringUtils.length(String.valueOf(row.get(column))));
                }
                definition.maxWidth(longest + CELL_PADDING);
            }
            toReturn.add(definition.with((List<?> row) -> String.valueOf(row.get(column))));
        }
        return toReturn;
    }
}
