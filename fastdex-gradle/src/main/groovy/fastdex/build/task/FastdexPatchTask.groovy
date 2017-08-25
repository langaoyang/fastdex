package fastdex.build.task

import fastdex.build.util.FastdexInstantRun
import fastdex.build.variant.FastdexVariant
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

/**
 * Created by tong on 17/3/12.
 */
public class FastdexPatchTask extends DefaultTask {
    FastdexVariant fastdexVariant

    FastdexPatchTask() {
        group = 'fastdex'
    }

    @TaskAction
    void patch() {

        FastdexInstantRun fastdexInstantRun = fastdexVariant.fastdexInstantRun
        println("##############哈哈哈 resourceChanged: ${fastdexInstantRun.resourceChanged}, sourceChanged: ${fastdexInstantRun.sourceChanged}")
    }
}
