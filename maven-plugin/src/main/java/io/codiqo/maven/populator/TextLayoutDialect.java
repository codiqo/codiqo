package io.codiqo.maven.populator;

import java.util.Set;

import org.thymeleaf.context.IExpressionContext;
import org.thymeleaf.dialect.AbstractDialect;
import org.thymeleaf.dialect.IExpressionObjectDialect;
import org.thymeleaf.expression.IExpressionObjectFactory;

/**
 * Makes {@link TextLayout} available to templates as {@code #layout} and {@link AsciiTables} as {@code #tables}, beside
 * Thymeleaf's own {@code #strings}. An expression object, unlike a context variable, stays reachable inside a
 * collection projection, which is where a template builds a table's cells.
 */
public class TextLayoutDialect extends AbstractDialect implements IExpressionObjectDialect {
    private static final String NAME = "layout";
    private static final String TABLES = "tables";
    private static final TextLayout LAYOUT = new TextLayout();
    private static final AsciiTables ASCII_TABLES = new AsciiTables();

    public TextLayoutDialect() {
        super(NAME);
    }
    @Override
    public IExpressionObjectFactory getExpressionObjectFactory() {
        return new IExpressionObjectFactory() {
            @Override
            public Set<String> getAllExpressionObjectNames() {
                return Set.of(NAME, TABLES);
            }
            @Override
            public Object buildObject(IExpressionContext context, String expressionObjectName) {
                return TABLES.equals(expressionObjectName) ? ASCII_TABLES : LAYOUT;
            }
            @Override
            public boolean isCacheable(String expressionObjectName) {
                return true;
            }
        };
    }
}
