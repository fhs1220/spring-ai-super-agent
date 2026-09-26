package com.fhs.aiagent.rag.multiagent;

import java.util.List;

public interface SystemOneShadowSampleRepository {

    SystemOneShadowSample save(SystemOneShadowSample sample);

    List<SystemOneShadowSample> findRecent(int limit);
}
