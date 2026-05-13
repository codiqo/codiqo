package io.codiqo.util;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.List;

import org.apache.commons.io.FileUtils;

import com.sun.management.OperatingSystemMXBean;

import lombok.experimental.UtilityClass;
import oshi.SystemInfo;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

@UtilityClass
public class MemoryReport {
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
        sb.append("memory[").append(checkpoint).append("] ");
        sb.append("heap=").append(human(heapUsed)).append('/').append(human(heapMax));
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

    private static String human(long bytes) {
        return FileUtils.byteCountToDisplaySize(bytes);
    }
}
