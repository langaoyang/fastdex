package fastdex.build.task

import fastdex.build.variant.FastdexVariant
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

/**
 * Created by tong on 17/3/12.
 */
public class FastdexInstantRunMarkTask extends DefaultTask {
    FastdexVariant fastdexVariant

    FastdexInstantRunMarkTask() {
        group = 'fastdex'
    }

    @TaskAction
    void mark() {
        fastdexVariant.fastdexInstantRun.fromFastdexInstantRun = true
        project.logger.error("==fastdex fromFastdexInstantRun: true")
    }
}
