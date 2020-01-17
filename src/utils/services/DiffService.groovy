package utils.services

import com.google.gson.Gson
import com.yit.deploy.core.support.AlgorithmSupport
import groovy.json.JsonOutput

class DiffService implements AlgorithmSupport {
    File sourcePath = new File("out/old-prod-vars.json")
    File targetPath = new File('out/prod-vars.json')
    File output = new File('out/changes.json')
    boolean ignoreAddedProjects = false
    boolean ignoreRemovedProjects = false
    boolean ignoreAddedVars = false
    boolean ignoreRemovedVars = false

    static Set KEYS_TO_IGNORE = [
        "HAPROXY_DEPLOY_DATETIME",
        "CANAL_SERVER_ID",
        "IMAGE_FULL_NAME_TO_CREATE",
        "deployInventory",
        "GET_CURRENT_DATE",
        "DB_BACKUP_FOLDER",
        "ALIYUN_RDS_RESTORE_ACKS",
        "ALIYUN_RDS_RESTORE_ACK",
        "HAPROXY_WHITE_LIST",
        "APIGW_EXTERNAL_WHITE_LIST",
        "HAPROXY_WHITE_LIST_COMMON",
        "CONFIG_MAP_TIMESTAMP",
        "timestamp"
    ]

    def diff() {
        assert this.sourcePath && this.targetPath && this.output

        def gson = new Gson()
        def source = gson.fromJson(this.sourcePath.text, Map)
        def target = gson.fromJson(this.targetPath.text, Map)

        def changes = compare(source, target, 0)

        this.output.write(JsonOutput.prettyPrint(toJson(changes)))
        println "changes are written into ${this.output.toURI()}"
    }

    def compare(def a, def b, int level) {

        if (a == b) {
            return null
        }

        if (a == null || b == null) {
            if (a == null) {
                return [add: b]
            } else {
                return [del: a]
            }
        }

        if (a instanceof Map && b instanceof Map) {
            def changes = [:]
            for (def entry in a) {
                if ((entry.key as String) in KEYS_TO_IGNORE) {
                    continue
                }

                if (level == 1 && this.ignoreRemovedVars) {
                    if (!b.containsKey(entry.key)) {
                        continue
                    }
                }
                if (level == 0 && this.ignoreRemovedProjects) {
                    if (!b.containsKey(entry.key)) {
                        continue
                    }
                }

                def c = compare(entry.value, b[entry.key], level + 1)
                if (c) {
                    changes[entry.key] = c
                }
            }
            for (def entry in b) {
                if (a.containsKey(entry.key)) {
                    continue
                }
                if (entry.key.toString().startsWith("\$")) {
                    continue
                }
                if (entry.key in KEYS_TO_IGNORE) {
                    continue
                }
                if (level == 1 && this.ignoreAddedVars) {
                    continue
                }
                if (level == 0 && this.ignoreAddedProjects) {
                    continue
                }
                changes[entry.key] = [add: entry.value]
            }
            if (changes.isEmpty()) {
                return null
            } else {
                return [changes: changes]
            }
        }

        if (a instanceof List && b instanceof List) {
            def changes = compareList(a, b, level + 1)
            if (changes.empty) {
                return null
            } else {
                return [changes: changes]
            }
        }

        return [fr: a, to: b]
    }

    List<Map> compareList(List a, List b, int level) {
        compareListRecursive(
            a,
            b,
            new HashSet<Integer>(),
            0,
            new DiffStatus(changes: [], score: 0),
            new DiffStatus(changes: [], score: Integer.MAX_VALUE),
            level
        ).changes
    }

    DiffStatus compareListRecursive(List a, List b, Set<Integer> match, int i, DiffStatus current, DiffStatus best, int level) {
        if (i >= a.size()) {
            def added = [], addedScore = 0
            for (int j = 0; j < b.size(); j++) {
                if (!match.contains(j)) {
                    // j is not matched in a
                    def c = [add: b[j]]
                    addedScore += evaluateChange(c)
                    added << c
                    if (addedScore + current.score >= best.score) {
                        return best // cut away
                    }
                }
            }

            if (current.changes.empty && added.empty || current.score + addedScore < best.score) {
                return [changes: current.changes + added, score: current.score + addedScore]
            } else {
                return best
            }
        }

        for (int j = 0; j <= b.size(); j++) {
            if (j < b.size() && match.contains(j)) {
                continue
            }

            def x = a[i], y = j == b.size() ? null : b[j]
            if (x instanceof Map && (x.key || x.name)) {
                if (y instanceof Map && (x.key ? x.key == y.key : x.name == y.name)) {
                    // this
                } else {
                    continue // quick path
                }
            }

            if (!(x instanceof Map || x instanceof List) && !(y instanceof Map || y instanceof List)) { // simple type
                if (x == y) {
                    // match
                } else {
                    continue // quick exit
                }
            }

            Map c = j == b.size() ? [del: a[i]] : compare(a[i], b[j], level + 1)

            def delta = evaluateChange(c)
            if (delta + current.score >= best.score) {
                continue // cut away
            }

            if (j < b.size()) {
                match.add(j)
            }
            current.score += delta
            if (c != null) {
                if (x instanceof Map && (x.key || x.name)) {
                    if (x.key) {
                        current.changes << [
                            key: x.key,
                            change: c
                        ]
                    } else {
                        current.changes << [
                            name: x.name,
                            change: c
                        ]
                    }
                } else {
                    current.changes << [
                        match: [
                            fr: i,
                            to: j
                        ],
                        change: c
                    ]
                }
            }

            best = compareListRecursive(a, b, match, i + 1, current, best, level)

            current.score -= delta
            if (c != null) {
                current.changes.pop()
            }

            if(j < b.size()) {
                match.remove(j)
            }

            if (best.changes.empty) {
                break
            }
        }

        return best
    }

    def evaluateChange(def change) {
        if (change == null) {
            return 0
        }

        if (change instanceof List) {
            int score = 1
            for (def c in change) {
                score += evaluateChange(c)
            }
            return score
        }

        if (change instanceof Map) {
            int score = 1
            for (def c in change.values()) {
                score += evaluateChange(c)
            }
            return score
        }

        return 1
    }

    private static class DiffStatus {
        List<Map> changes
        int score
    }

    static void main(String[] args) {
        def service = new DiffService()
        def method = {service.diff()}
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case '-t':
                    service.targetPath = new File(args[++i])
                    break
                case '-s':
                    service.sourcePath = new File(args[++i])
                    break
                case '-o':
                    service.output = new File(args[++i])
                    break
                case 'diff':
                    method = {service.diff()}
                    break
            }
        }

        if (method) {
            method()
        }
    }
}
