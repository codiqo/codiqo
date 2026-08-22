package io.codiqo.util;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.openjdk.jol.info.GraphLayout;

import com.sun.management.OperatingSystemMXBean;

import lombok.experimental.UtilityClass;
import oshi.SystemInfo;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

@UtilityClass
public class MemoryReport {
    /**
     * Deep object-graph measurement walks every reachable reference and keeps an identity set of what it has seen,
     * so on the very structures worth measuring it costs about as much memory as the thing it is measuring. Off
     * unless asked for, which keeps a normal run paying nothing for it.
     */
    public static final String PROFILING_PROPERTY = "codiqo.memoryProfiling";

    /**
     * JOL reads field offsets through Unsafe, which a strongly-encapsulated JDK refuses for classes like Thread —
     * and an analysis graph reaches those. Setting this before JOL loads buys the traversal back; set here rather
     * than demanded as a JVM flag, and never over an explicit choice.
     */
    private static final String JOL_MAGIC_FIELD_OFFSET = "jol.magicFieldOffset";

    private static final SystemInfo SYSTEM_INFO = new SystemInfo();
    private static final OperatingSystemMXBean OS_BEAN = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    public static String snapshot(String checkpoint) {
        Runtime rt = Runtime.getRuntime();
        long heapUsed = rt.totalMemory() - rt.freeMemory();
        long heapMax = rt.maxMemory();

        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage nonHeap = memBean.getNonHeapMemoryUsage();

        OperatingSystem os = SYSTEM_INFO.getOperatingSystem();
        OSProcess self = os.getCurrentProcess();
        List<OSProcess> descendants = os.getDescendantProcesses(self.getProcessID(), null, null, 0);
        long descendantsRss = descendants.stream().mapToLong(OSProcess::getResidentMemory).sum();

        long totalPhys = OS_BEAN.getTotalMemorySize();
        long freePhys = OS_BEAN.getFreeMemorySize();

        StringBuilder sb = new StringBuilder(192);
        sb.append("memory (").append(checkpoint).append(")");
        sb.append(" heap=").append(human(heapUsed)).append('/').append(human(heapMax));
        sb.append(" non-heap=").append(human(nonHeap.getUsed()));
        sb.append(" rss=").append(human(self.getResidentMemory()));
        sb.append(" virt=").append(human(self.getVirtualSize()));
        if (!descendants.isEmpty()) {
            sb.append(" forks=").append(descendants.size()).append('(').append(human(descendantsRss)).append(')');
        }
        sb.append(" sys=").append(human(totalPhys - freePhys)).append('/').append(human(totalPhys));
        sb.append(" free=").append(human(freePhys));

        return sb.toString();
    }
    public static long peakHeapUsed() {
        return heapPools().stream().mapToLong(pool -> pool.getPeakUsage().getUsed()).sum();
    }
    public static void resetHeapPeak() {
        heapPools().forEach(MemoryPoolMXBean::resetPeakUsage);
    }
    public static long heapUsed() {
        return heapPools().stream().mapToLong(pool -> pool.getUsage().getUsed()).sum();
    }
    public static Optional<String> retained(Object... roots) {
        if (BooleanUtils.negate(isProfiling())) {
            return Optional.empty();
        }
        if (Objects.isNull(System.getProperty(JOL_MAGIC_FIELD_OFFSET))) {
            System.setProperty(JOL_MAGIC_FIELD_OFFSET, Boolean.TRUE.toString());
        }
        try {
            return Optional.of(human(GraphLayout.parseInstance(roots).totalSize()));
        } catch (RuntimeException err) {
            return Optional.of("unavailable (" + err.getClass().getSimpleName() + ": " + err.getMessage() + ")");
        }
    }
    public static boolean isProfiling() {
        return Boolean.parseBoolean(System.getProperty(PROFILING_PROPERTY, Boolean.FALSE.toString()));
    }
    public static String human(long bytes) {
        return FileUtils.byteCountToDisplaySize(bytes);
    }
    private static List<MemoryPoolMXBean> heapPools() {
        return ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getType() == MemoryType.HEAP)
                .toList();
    }
}
