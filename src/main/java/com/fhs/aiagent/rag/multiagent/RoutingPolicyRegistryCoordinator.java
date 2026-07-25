package com.fhs.aiagent.rag.multiagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 协调动态轨迹策略与独立注册表，避免注册表和运行时策略形成循环依赖。
 */
@Service
public class RoutingPolicyRegistryCoordinator {

    private static final Logger log =
            LoggerFactory.getLogger(RoutingPolicyRegistryCoordinator.class);

    private final RoutingPolicyRegistryService registryService;

    private final TrajectoryAwareRoutingPolicy routingPolicy;

    public RoutingPolicyRegistryCoordinator(
            RoutingPolicyRegistryService registryService,
            TrajectoryAwareRoutingPolicy routingPolicy) {
        this.registryService = registryService;
        this.routingPolicy = routingPolicy;
    }

    @Scheduled(
            initialDelayString =
                    "${agent.rag.routing-policy.registry.reconcile-interval-ms:60000}",
            fixedDelayString =
                    "${agent.rag.routing-policy.registry.reconcile-interval-ms:60000}")
    public void scheduledReconcile() {
        try {
            reconcileNow();
        } catch (RuntimeException exception) {
            log.error("路由策略注册表自动对账失败", exception);
        }
    }

    public RoutingPolicyRegistryState reconcileNow() {
        return registryService.reconcileNow(
                routingPolicy.status(),
                routingPolicy.learnedPolicySnapshot()
        );
    }
}
