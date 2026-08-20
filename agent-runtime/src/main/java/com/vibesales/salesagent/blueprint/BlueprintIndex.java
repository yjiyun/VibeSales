package com.agentteams.salesagent.blueprint;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * classpath 蓝图目录索引。
 *
 * <p>为什么需要一份显式索引，而不是扫描 {@code blueprints/} 目录：classpath 目录枚举在 jar 环境下
 * 不可靠（{@code getResource("blueprints/")} 返回的 jar URL 无法直接当目录遍历）。用一份索引文件把
 * "有哪些租户蓝图"显式写出来，本地跑 {@code target/classes} 和打包成 jar 两种方式行为一致。
 *
 * <p>索引项的路由键是 {@code clientCode + cluster} 两级：带 {@code cluster} 的条目是该集群的专属蓝图，
 * {@code cluster} 留空的条目是该租户的默认蓝图（{@code cluster} 找不到时降级到它）。每个
 * {@code clientCode} 至多一条空 {@code cluster} 条目，否则降级目标不确定。
 *
 * <p>{@code cluster} 同时出现在索引项和蓝图 JSON 里：索引项决定路由，JSON 里的是蓝图自描述。
 * 两处不一致由 {@link AgentBlueprintValidator} 按 error 拦下，避免蓝图放错槽位导致串集群。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BlueprintIndex(List<Entry> entries) {

    public BlueprintIndex {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public static BlueprintIndex empty() {
        return new BlueprintIndex(List.of());
    }

    /**
     * @param clientCode 租户主键
     * @param cluster 集群键；留空表示该 clientCode 的默认蓝图（降级目标）
     * @param path Blueprint JSON 的 classpath 路径
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(String clientCode, String cluster, String path) {}
}
