package io.codiqo.submit.hotspots;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HotspotFindingsTest {
    @Test
    void membersReadAsMethodNameAndParameterTypes() {
        assertEquals("toAccountInfo(Account, Set)", HotspotFindings.readable("shop/dto/Mappers.toAccountInfo(Lshop/model/Account;Ljava/util/Set;)Lshop/api/AccountInfo;"));
        assertEquals("apply(int[], Map.Entry, boolean)", HotspotFindings.readable("a/B.apply([ILjava/util/Map$Entry;Z)V"));
        assertEquals("Mappers()", HotspotFindings.readable("shop/dto/Mappers.<init>()V"));
        assertEquals("Mappers()", HotspotFindings.readable("Mappers.<init>()V"));
        assertEquals("B.C(String)", HotspotFindings.readable("a/B$C.<init>(Ljava/lang/String;)V"));
    }
}
