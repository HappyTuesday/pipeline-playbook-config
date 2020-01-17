package projects

import groovy.transform.BaseScript
import com.yit.deploy.core.dsl.parse.ProjectBaseScript
@BaseScript ProjectBaseScript $this

PROJECT_NAME = lazy {project.projectName}

projectNameGenerator { key -> key }
jobName lazy {"$env.name-$project.projectName"}

jobOrder abstractedVariable()
containerLabels([])
dependencies([])
schedule null
section abstractedVariable()
group null
skipTasks([])
requireTasks([])

variableGroup { p ->
    p.projectName.replace('-', '_').toUpperCase()
}

gitRepositoryUrl lazy {
    job.projectGitName ? "$GIT_SSH_CLONE_URL/${job.projectGitName}.git" as String : null
}

/*
override语法：
1. override可以嵌套
2. override的每一个参数都可以是一个环境查询表达式

环境查询表达式：
1. 【简单模式】可以是一个环境名、环境的标签、环境（匹配的那个环境以及所有继承它的环境均属于查询结果），例如testenv表示所有的测试环境
2. 【取反】可以将！加到查询表达式的前面，以表示查询所有不匹配的环境，例如 !prod表示所有非生产环境，!k8s表示所有非k8s环境
3. 【取交集】可以用&将两个表达式连接到一起，以表示只查询同时匹配这两个表达式的环境集合，例如 testenv & k8s表示所有k8s的测试环境，
4. 【取并集】可以用 : 将两个表达式连接到一起，以表示查询满足第一个或第二个表达式的环境，例如 prod : perf表示生产环境或性能测试环境
5. 【优先级】优先级由高到低：! -> & -> :，例如prod : k8s & stage表示生产环境或开启了k8s的stage环境
6. 【改变优先级】可以使用小括号改变表达式中出现的优先级，例如 !(prod : perf)表示除生产和性能测试之外的环境。

环境表达式可以用于includeEnv / excludeEnv / includeOnlyEnv
*/