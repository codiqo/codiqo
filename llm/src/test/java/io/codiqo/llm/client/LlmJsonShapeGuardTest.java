package io.codiqo.llm.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.junit.jupiter.api.Test;

import com.google.common.collect.Lists;
import com.google.common.collect.Queues;
import com.google.common.collect.Sets;

import io.codiqo.llm.schema.LlmScoringRequest;
import io.codiqo.llm.schema.LlmScoringResponse;
import io.codiqo.llm.schema.RecapAwardsResponse;
import io.codiqo.llm.schema.SkipRequestResponse;
import io.codiqo.llm.schema.TagConsolidationResponse;

/**
 * A commons-lang3 tuple is a {@code Map.Entry}, which Jackson writes as {@code {key: value}} instead of a bean, so a
 * tuple anywhere in an LLM request or response type would silently change the JSON shape the model sees or returns.
 * This walks every type reachable from the JSON roots and fails on any tuple.
 */
class LlmJsonShapeGuardTest {
    private static final String CODIQO_PACKAGE = "io.codiqo.";

    @Test
    void noJsonTypeIsATuple() throws Exception {
        List<Class<?>> roots = List.of(
                LlmScoringRequest.class,
                LlmScoringResponse.class,
                TagConsolidationResponse.class,
                RecapAwardsResponse.class,
                SkipRequestResponse.class,
                WebSearchTool.class,
                Class.forName(OllamaWebSearchClient.class.getName() + "$SearchRequest"),
                Class.forName(OllamaWebSearchClient.class.getName() + "$SearchResponse"));

        Set<Class<?>> reachable = reachable(roots);
        List<String> tuples = reachable.stream()
                .filter(type -> Map.Entry.class.isAssignableFrom(type) || Pair.class.isAssignableFrom(type) || Triple.class.isAssignableFrom(type))
                .map(Class::getName)
                .toList();

        assertTrue(reachable.size() > roots.size(), "the walk must reach the nested schema types");
        assertEquals(List.of(), tuples, "JSON-mapped types must stay plain beans, not tuples");
    }

    private static Set<Class<?>> reachable(List<Class<?>> roots) {
        Set<Class<?>> toReturn = Sets.newLinkedHashSet();
        Deque<Class<?>> pending = Queues.newArrayDeque(roots);
        while (!pending.isEmpty()) {
            Class<?> type = pending.pop();
            if (toReturn.add(type) && type.getName().startsWith(CODIQO_PACKAGE) && !type.isEnum()) {
                for (Field field : JsonFixtures.fields(type)) {
                    for (Class<?> referenced : classesIn(field.getGenericType())) {
                        pending.push(referenced);
                    }
                }
            }
        }
        return toReturn;
    }
    private static List<Class<?>> classesIn(Type type) {
        List<Class<?>> toReturn = Lists.newArrayList();
        if (type instanceof Class<?> cls) {
            toReturn.add(cls.isArray() ? cls.getComponentType() : cls);
        } else if (type instanceof ParameterizedType parameterized) {
            toReturn.addAll(classesIn(parameterized.getRawType()));
            for (Type argument : parameterized.getActualTypeArguments()) {
                toReturn.addAll(classesIn(argument));
            }
        } else if (type instanceof WildcardType wildcard) {
            for (Type bound : wildcard.getUpperBounds()) {
                toReturn.addAll(classesIn(bound));
            }
        }
        return toReturn;
    }
}
