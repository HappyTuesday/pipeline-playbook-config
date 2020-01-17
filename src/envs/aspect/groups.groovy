package envs.aspect

import com.yit.deploy.core.dsl.parse.EnvironmentBaseScript
import com.yit.deploy.core.model.ConnectionChannel
import groovy.transform.BaseScript
@BaseScript EnvironmentBaseScript $this

/*
 * For the convenience of managing all hosts and their host groups and the relationship of the host groups,
 * we proposed a new host group model based on network:
 *
 * 1. A host group can declare zero or more hosts inside it.
 *    It is called this hosts are directly included by that host group.
 *
 * 2. A host can be directly included by several host groups.
 *
 * 3. A host group can declare inheritance to zero or more host groups.
 *    It is called this host group directly inherits those host groups.
 *    It is called that a host group (called A) which inherits host group (called B) which inherits host group (called C)
 *    indirectly inherits host group C.
 *    The inherited groups are called the parent of the inheriting groups, while the inheriting groups is called the children
 *    of the inherited groups.
 *
 * 4. The host groups themselves are the nodes of the network, with the directly inheritance the arrows in the network
 *    from the parent groups to the children groups.
 *
 * 5. Given an arbitrary host group named x, we defined following functions for convenience:
 *      a. f(x) == all host groups which directly / indirectly inherits a host group x plus x itself.
 *      b. g(x) == all host groups which are directly / indirectly inherited by a host group x plus x itself.
 *    When we query the hosts of a given host group x (hosts(x)), following steps are performed:
 *      a. Let host group collection A = f(x)
 *      b. Let host group collection B = g(y) for every host group y in A
 *      c. Let host collection C = all hosts directly included by every host group z in B
 *      d. Return C
 *    So that the collection C contains directly included hosts and indirectly included hosts by the host group x.
 *
 * 6. A host can be declared as retired in a host group, which will not be returned from the query hosts(x)
 *
 */

abstracted()

inherits "aspect.vars"

defaultDeployUser {DEPLOY_USER}

host "localhost" channel ConnectionChannel.local user JENKINS_USER

group "all"

group ("localhost") {
    inherits "all"
    host "localhost"
}

group "standalone" inherits "all"

// groups that inherits groups defined above
group "infrastructure" inherits "standalone"

group "business-kafka" inherits "infrastructure"
group "business-kafka-zk" inherits "infrastructure"

group "log-kafka" inherits "infrastructure"
group "log-kafka-zk" inherits "infrastructure"