package io.codiqo.submit.hotspots;

import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.math3.stat.descriptive.moment.GeometricMean;
import org.apache.commons.math3.stat.ranking.NaNStrategy;
import org.apache.commons.math3.stat.ranking.NaturalRanking;
import org.apache.commons.math3.stat.ranking.TiesStrategy;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.Multiset;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;

import io.codiqo.api.code.DeclaredType;
import io.codiqo.api.code.TypeKind;
import io.codiqo.api.code.TypeReference;
import io.codiqo.api.code.TypeReferenceKind;
import io.codiqo.submit.hotspots.GitChurn.FileChurn;
import lombok.Value;
import lombok.experimental.UtilityClass;

/**
 * Importance fuses dependents with size × history × dependencies: a composition root or a large service has few
 * dependents yet matters as much as a core entity. Hotspots leave centrality out on purpose — measured against expert
 * reviews it made the ranking worse.
 */
@UtilityClass
public class HotspotRanker {
    public static final int IMPORTANT_LIMIT = 128;
    public static final int HOTSPOT_LIMIT = 64;

    /** The reciprocal-rank-fusion constant. Any value from 5 to 30 gave the same ranking on the validation project. */
    private static final int FUSION_K = 20;

    /**
     * Callers of an interface or abstract class really depend on whatever implements it, so each implementation is
     * credited an equal share of the abstraction's callers in its effective fan-in. Without it an implementation
     * reached only through its interface would rank as if nothing used it.
     */
    private static final EnumSet<TypeKind> ABSTRACTIONS = EnumSet.of(TypeKind.INTERFACE, TypeKind.ABSTRACT_CLASS);

    public List<RankedType> rank(Collection<DeclaredType> types, Collection<TypeReference> references, Function<DeclaredType, String> paths, Map<String, FileChurn> churn) {
        /**
         * Modules are parsed in parallel, so arrival order is not stable: a name two modules declare is settled by
         * path, or the same commit could rank a different class on every run.
         */
        Map<String, DeclaredType> byName = Maps.newLinkedHashMap();
        types.stream()
                .sorted(Comparator.comparing(DeclaredType::getName).thenComparing(type -> type.getFile().getPath()))
                .forEach(type -> byName.putIfAbsent(type.getName(), type));

        SetMultimap<String, String> dependents = MultimapBuilder.hashKeys().hashSetValues().build();
        SetMultimap<String, String> dependencies = MultimapBuilder.hashKeys().hashSetValues().build();
        SetMultimap<String, String> implementations = MultimapBuilder.hashKeys().hashSetValues().build();
        for (TypeReference reference : references) {
            if (byName.containsKey(reference.getFrom()) && byName.containsKey(reference.getTo())) {
                dependents.put(reference.getTo(), reference.getFrom());
                dependencies.put(reference.getFrom(), reference.getTo());
                if (reference.getKind() == TypeReferenceKind.INHERIT && ABSTRACTIONS.contains(byName.get(reference.getTo()).getKind())) {
                    implementations.put(reference.getTo(), reference.getFrom());
                }
            }
        }

        Map<String, Double> effective = Maps.newHashMap();
        byName.keySet().forEach(name -> effective.put(name, (double) dependents.get(name).size()));
        for (String abstraction : implementations.keySet()) {
            Set<String> callers = Sets.newHashSet(dependents.get(abstraction));
            callers.removeAll(implementations.get(abstraction));
            double share = (double) callers.size() / implementations.get(abstraction).size();
            implementations.get(abstraction).forEach(implementation -> effective.merge(implementation, share, Double::sum));
        }

        Map<String, FileChurn> churnByType = Maps.newHashMap();
        byName.forEach((name, type) -> churnByType.put(name, churn.getOrDefault(paths.apply(type), FileChurn.NONE)));

        ToDoubleFunction<String> size = name -> byName.get(name).getNcss();
        ToDoubleFunction<String> commits = name -> churnByType.get(name).getCommits();
        ToDoubleFunction<String> recent = name -> churnByType.get(name).getRecentCommits();
        ToDoubleFunction<String> fixes = name -> churnByType.get(name).getRecentFixCommits();
        ToDoubleFunction<String> fanOut = name -> dependencies.get(name).size();

        Collection<String> names = byName.keySet();
        Map<String, Double> mass = geometricMean(names, List.of(percentiles(names, size), percentiles(names, commits), percentiles(names, fanOut)));
        Map<String, Double> heat = geometricMean(names, List.of(percentiles(names, recent), percentiles(names, size), percentiles(names, fixes)));

        /** Tied scores share their best position, so a class's name cannot tilt the fused score. */
        Map<String, Double> importance = Maps.newHashMap();
        for (Map<String, Double> scores : List.of(effective, mass)) {
            positions(names, scores).forEach((name, position) -> importance.merge(name, 1.0 / (FUSION_K + position), Double::sum));
        }

        Map<String, Integer> importanceRanks = ranks(names, importance, IMPORTANT_LIMIT);
        List<String> active = names.stream().filter(name -> recent.applyAsDouble(name) > 0).toList();
        Map<String, Integer> hotspotRanks = ranks(active, heat, HOTSPOT_LIMIT);

        List<RankedType> toReturn = Lists.newArrayList();
        for (String name : names) {
            if (importanceRanks.containsKey(name) || hotspotRanks.containsKey(name)) {
                DeclaredType type = byName.get(name);
                toReturn.add(new RankedType(
                        type,
                        paths.apply(type),
                        importanceRanks.get(name),
                        hotspotRanks.get(name),
                        dependents.get(name).size(),
                        effective.get(name),
                        dependencies.get(name).size(),
                        churnByType.get(name)));
            }
        }
        toReturn.sort(Comparator.comparing(RankedType::bestRank).thenComparing(ranked -> ranked.getType().getName()));
        return toReturn;
    }
    /**
     * A tie crossing the limit is left out whole, so the name never decides who makes the cut and a snapshot never
     * exceeds the API's cap of 192 classes.
     */
    private static Map<String, Integer> ranks(Collection<String> names, Map<String, Double> scores, int limit) {
        Map<String, Double> positions = positions(names, scores);
        Multiset<Double> tied = HashMultiset.create(positions.values());

        Map<String, Integer> toReturn = Maps.newHashMap();
        positions.forEach((name, position) -> {
            if (position + tied.count(position) - 1 <= limit) {
                toReturn.put(name, position.intValue());
            }
        });
        return toReturn;
    }
    private static Map<String, Double> positions(Collection<String> names, Map<String, Double> scores) {
        Map<String, Double> toReturn = Maps.newHashMap();
        /**
         * NaturalRanking throws on an empty array, and a project without recent commits has no active classes, so
         * the hotspot ranking would fail on it.
         */
        if (CollectionUtils.isNotEmpty(names)) {
            List<String> order = List.copyOf(names);
            double[] positions = new NaturalRanking(NaNStrategy.FAILED, TiesStrategy.MINIMUM).rank(order.stream().mapToDouble(name -> -scores.get(name)).toArray());
            for (int i = 0; i < order.size(); i++) {
                toReturn.put(order.get(i), positions[i]);
            }
        }
        return toReturn;
    }
    /** Percentiles are never zero (the lowest rank divided by the count), so the geometric mean stays defined. */
    private static Map<String, Double> percentiles(Collection<String> names, ToDoubleFunction<String> signal) {
        List<String> order = List.copyOf(names);
        double[] ranks = new NaturalRanking(NaNStrategy.FAILED, TiesStrategy.MAXIMUM).rank(order.stream().mapToDouble(signal).toArray());
        Map<String, Double> toReturn = Maps.newHashMap();
        for (int i = 0; i < order.size(); i++) {
            toReturn.put(order.get(i), ranks[i] / order.size());
        }
        return toReturn;
    }
    private static Map<String, Double> geometricMean(Collection<String> names, List<Map<String, Double>> signals) {
        GeometricMean mean = new GeometricMean();
        Map<String, Double> toReturn = Maps.newHashMap();
        for (String name : names) {
            toReturn.put(name, mean.evaluate(signals.stream().mapToDouble(signal -> signal.get(name)).toArray()));
        }
        return toReturn;
    }

    @Value
    public static class RankedType {
        DeclaredType type;
        String path;
        Integer importanceRank;
        Integer hotspotRank;
        int dependents;
        double effectiveDependents;
        int dependencies;
        FileChurn churn;

        @VisibleForTesting
        public int bestRank() {
            int toReturn = Integer.MAX_VALUE;
            if (Objects.nonNull(importanceRank)) {
                toReturn = importanceRank;
            }
            if (Objects.nonNull(hotspotRank)) {
                toReturn = Math.min(toReturn, hotspotRank);
            }
            return toReturn;
        }
    }
}
