package com.lian.aicode.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证生成任务与应用删除之间的进程内互斥边界。 */
class GenerationTaskManagerTest {

    @Test
    void generationAndDeleteCannotEnterCriticalSectionTogether() {
        GenerationTaskManager manager = new GenerationTaskManager();
        GenerationTaskManager.GenerationTask task = manager.start(1L);

        assertFalse(manager.beginDelete(1L));
        manager.cancel(1L);
        manager.finish(1L, task);

        assertTrue(manager.beginDelete(1L));
        assertThrows(RuntimeException.class, () -> manager.start(1L));
        manager.finishDelete(1L);
        assertNotNull(manager.start(1L));
    }
}
