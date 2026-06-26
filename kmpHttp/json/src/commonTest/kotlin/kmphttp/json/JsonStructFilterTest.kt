package kmphttp.json

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonStructFilterTest {

    @Test
    fun testIncludeAllDefault() {
        val filter = JsonStructFilter.includeAll {}
        assertTrue(filter.include("anyKey"))
        assertTrue(filter.include(0))
        assertTrue(filter.include(100))
    }

    @Test
    fun testExcludeAllDefault() {
        val filter = JsonStructFilter.excludeAll {}
        assertFalse(filter.include("anyKey"))
        assertFalse(filter.include(0))
        assertFalse(filter.include(100))
    }

    @Test
    fun testIncludeAllExceptKey() {
        val filter = JsonStructFilter.includeAll {
            except("skip")
            except("ignore")
        }
        assertTrue(filter.include("name"))
        assertTrue(filter.include("age"))
        assertFalse(filter.include("skip"))
        assertFalse(filter.include("ignore"))
    }

    @Test
    fun testIncludeAllExceptKeyPredicate() {
        val filter = JsonStructFilter.includeAll {
            except { it.startsWith("_") }
        }
        assertTrue(filter.include("name"))
        assertFalse(filter.include("_private"))
        assertFalse(filter.include("_internal"))
    }

    @Test
    fun testIncludeAllExceptIndex() {
        val filter = JsonStructFilter.includeAll {
            exceptIndex(0)
            exceptIndex(5)
        }
        assertFalse(filter.include(0))
        assertTrue(filter.include(1))
        assertTrue(filter.include(4))
        assertFalse(filter.include(5))
    }

    @Test
    fun testIncludeAllExceptIndexPredicate() {
        val filter = JsonStructFilter.includeAll {
            exceptIndex { it % 2 == 0 } // Skip even indices
        }
        assertFalse(filter.include(0))
        assertTrue(filter.include(1))
        assertFalse(filter.include(2))
        assertTrue(filter.include(3))
    }

    @Test
    fun testExcludeAllIncludeKey() {
        val filter = JsonStructFilter.excludeAll {
            except("name")
            except("age")
        }
        assertTrue(filter.include("name"))
        assertTrue(filter.include("age"))
        assertFalse(filter.include("other"))
    }

    @Test
    fun testExcludeAllIncludeKeyPredicate() {
        val filter = JsonStructFilter.excludeAll {
            except { it.startsWith("public_") }
        }
        assertTrue(filter.include("public_name"))
        assertTrue(filter.include("public_age"))
        assertFalse(filter.include("private_data"))
    }

    @Test
    fun testNestedFilter() {
        val filter = JsonStructFilter.includeAll {
            nested("child", JsonStructFilter.excludeAll {
                except("name")
            })
        }
        assertTrue(filter.include("child"))
        assertTrue(filter.include("other"))

        val childFilter = assertNotNull(filter.nested("child"))
        assertTrue(childFilter.include("name"))
        assertFalse(childFilter.include("other"))

        assertNull(filter.nested("other"))
    }

    @Test
    fun testWildcardNestedFilter() {
        val filter = JsonStructFilter.includeAll {
            nested("*", JsonStructFilter.excludeAll {
                except("id")
            })
        }

        val anyFilter = assertNotNull(filter.nested("anyKey"))
        assertTrue(anyFilter.include("id"))
        assertFalse(anyFilter.include("other"))
    }

    @Test
    fun testFirstMatchWins() {
        // First rule matches "a" specifically
        val filter = JsonStructFilter.includeAll {
            nested("a", JsonStructFilter.excludeAll {
                except("first")
            })
            nested("*", JsonStructFilter.excludeAll {
                except("second")
            })
        }

        // "a" should use first rule
        val aFilter = assertNotNull(filter.nested("a"))
        assertTrue(aFilter.include("first"))
        assertFalse(aFilter.include("second"))

        // "b" should use wildcard rule
        val bFilter = assertNotNull(filter.nested("b"))
        assertFalse(bFilter.include("first"))
        assertTrue(bFilter.include("second"))
    }

    @Test
    fun testWildcardFirstMatchWins() {
        // Wildcard declared first should match first
        val filter = JsonStructFilter.includeAll {
            nested("*", JsonStructFilter.excludeAll {
                except("wildcard")
            })
            nested("a", JsonStructFilter.excludeAll {
                except("specific")
            })
        }

        // Even "a" should use wildcard rule since it's first
        val aFilter = assertNotNull(filter.nested("a"))
        assertTrue(aFilter.include("wildcard"))
        assertFalse(aFilter.include("specific"))
    }

    @Test
    fun testPathPattern() {
        // Pattern: */abc means match any key, then "abc"
        val filter = JsonStructFilter.includeAll {
            nested("*/abc", JsonStructFilter.excludeAll {
                except("target")
            })
        }

        // First level: any key matches
        val level1 = assertNotNull(filter.nested("foo"))

        // Second level: only "abc" matches
        val level2 = assertNotNull(level1.nested("abc"))
        assertTrue(level2.include("target"))
        assertFalse(level2.include("other"))

        // "xyz" doesn't match at second level
        assertNull(level1.nested("xyz"))
    }

    @Test
    fun testAnyDepthPattern() {
        // Pattern: **/target means match any depth then "target"
        val filter = JsonStructFilter.includeAll {
            nested("**/target", JsonStructFilter.excludeAll {
                except("found")
            })
        }

        // Can match at any depth: a/b/c/target
        var current: JsonStructFilter = filter
        current = assertNotNull(current.nested("a"))
        current = assertNotNull(current.nested("b"))
        current = assertNotNull(current.nested("c"))
        current = assertNotNull(current.nested("target"))

        assertTrue(current.include("found"))
        assertFalse(current.include("other"))
    }

    @Test
    fun testReadObjectWithFilter() = runTest {
        val json = """{"name": "John", "age": 30, "skip": "ignored", "data": "kept"}"""
        val filter = JsonStructFilter.includeAll {
            except("skip")
        }

        val reader = json.toJsonReader()
        val result = reader.readObject(filter)

        assertEquals(3, result.size)
        assertEquals("John", result["name"]?.jsonPrimitive?.content)
        assertEquals("30", result["age"]?.jsonPrimitive?.content)
        assertEquals("kept", result["data"]?.jsonPrimitive?.content)
        assertNull(result["skip"])
    }

    @Test
    fun testReadObjectWithNestedFilter() = runTest {
        val json = """{
            "user": {"name": "John", "password": "secret", "age": 30},
            "metadata": "kept"
        }"""
        val filter = JsonStructFilter.includeAll {
            nested("user", JsonStructFilter.includeAll {
                except("password")
            })
        }

        val reader = json.toJsonReader()
        val result = reader.readObject(filter)

        assertEquals(2, result.size)
        val user = assertNotNull(result["user"]?.jsonObject)
        assertEquals(2, user.size)
        assertEquals("John", user["name"]?.jsonPrimitive?.content)
        assertEquals("30", user["age"]?.jsonPrimitive?.content)
        assertNull(user["password"])
    }

    @Test
    fun testReadArrayWithFilter() = runTest {
        val json = """[1, 2, 3, 4, 5]"""
        val filter = JsonStructFilter.includeAll {
            exceptIndex(1)
            exceptIndex(3)
        }

        val reader = json.toJsonReader()
        val result = reader.readArray(filter)

        assertEquals(3, result.size)
        assertEquals("1", result[0].jsonPrimitive.content)
        assertEquals("3", result[1].jsonPrimitive.content)
        assertEquals("5", result[2].jsonPrimitive.content)
    }

    @Test
    fun testExcludeAllWithNestedNeedsExplicitExcept() {
        // nested() does NOT auto-include the key, need explicit except()
        val filter = JsonStructFilter.excludeAll {
            nested("keep", JsonStructFilter.includeAll {})
        }

        assertFalse(filter.include("keep")) // NOT auto-included, need except("keep")
        assertFalse(filter.include("other"))

        // Correct way: use except() to include the key
        val filterWithExcept = JsonStructFilter.excludeAll {
            except("keep")
            nested("keep", JsonStructFilter.includeAll {})
        }
        assertTrue(filterWithExcept.include("keep"))
    }

    @Test
    fun testAnyDepthWithExactKeyInMiddle() {
        // Pattern: **/target/*/abc
        // Should match: any/depth/target/something/abc
        val filter = JsonStructFilter.includeAll {
            nested("**/target/*/abc", JsonStructFilter.excludeAll {
                except("found")
            })
        }

        // a/b/target/x/abc
        var current: JsonStructFilter = filter
        current = assertNotNull(current.nested("a"), "Should match 'a' via **")
        current = assertNotNull(current.nested("b"), "Should match 'b' via **")
        current = assertNotNull(current.nested("target"), "Should match 'target'")
        current = assertNotNull(current.nested("x"), "Should match 'x' via *")
        current = assertNotNull(current.nested("abc"), "Should match 'abc' and reach terminal")

        assertTrue(current.include("found"))
        assertFalse(current.include("other"))
    }

    @Test
    fun testAnyDepthWithExactKeyInMiddle_Backtrack() {
        // Pattern: **/target/*/abc
        // With backtracking: a/b/target/x/xyz continues with ** (doesn't fail)
        val filter = JsonStructFilter.includeAll {
            nested("**/target/*/abc", JsonStructFilter.excludeAll {
                except("found")
            })
        }

        var current: JsonStructFilter = filter
        // a/b/target/x/xyz - "xyz" backtracks to **, still looking for target/*/abc
        current = assertNotNull(current.nested("a"))
        current = assertNotNull(current.nested("b"))
        current = assertNotNull(current.nested("target"))
        current = assertNotNull(current.nested("x"))
        current = assertNotNull(current.nested("xyz"), "Should backtrack to ** and continue")

        // Now we can still find target/y/abc
        current = assertNotNull(current.nested("target"))
        current = assertNotNull(current.nested("y"))
        current = assertNotNull(current.nested("abc"))
        assertTrue(current.include("found"))
    }

    @Test
    fun testAnyDepthMatchesAtRoot() {
        // Pattern: **/target means "target" can appear at any level including root
        val filter = JsonStructFilter.includeAll {
            nested("**/target", JsonStructFilter.excludeAll {
                except("found")
            })
        }

        // Direct match at root
        val directMatch = assertNotNull(filter.nested("target"), "** should match 'target' at root level")
        assertTrue(directMatch.include("found"))
    }

    @Test
    fun testComplexPatternWithMultipleAnyDepth() {
        // Pattern: **/a/**/b
        val filter = JsonStructFilter.includeAll {
            nested("**/a/**/b", JsonStructFilter.excludeAll {
                except("found")
            })
        }

        // x/y/a/z/w/b
        var current: JsonStructFilter = filter
        current = assertNotNull(current.nested("x"))
        current = assertNotNull(current.nested("y"))
        current = assertNotNull(current.nested("a"))
        current = assertNotNull(current.nested("z"))
        current = assertNotNull(current.nested("w"))
        current = assertNotNull(current.nested("b"))

        assertTrue(current.include("found"))
    }

    @Test
    fun testPathPatternMultipleLevels() {
        // Pattern: a/b/c (exact 3-level path)
        val filter = JsonStructFilter.includeAll {
            nested("a/b/c", JsonStructFilter.excludeAll {
                except("target")
            })
        }

        var current: JsonStructFilter = filter
        current = assertNotNull(current.nested("a"))
        current = assertNotNull(current.nested("b"))
        current = assertNotNull(current.nested("c"))
        assertTrue(current.include("target"))

        // Wrong path
        assertNull(filter.nested("x"), "Should not match 'x', expected 'a'")
    }

    @Test
    fun testWildcardAtMultipleLevels() {
        // Pattern: */*/target
        val filter = JsonStructFilter.includeAll {
            nested("*/*/target", JsonStructFilter.excludeAll {
                except("found")
            })
        }

        // any/any/target
        var current: JsonStructFilter = filter
        current = assertNotNull(current.nested("foo"))
        current = assertNotNull(current.nested("bar"))
        current = assertNotNull(current.nested("target"))
        assertTrue(current.include("found"))

        // Wrong key at level 3
        val level2 = assertNotNull(filter.nested("x")?.nested("y"))
        assertNull(level2.nested("wrong"), "Should not match 'wrong', expected 'target'")
    }

    @Test
    fun testAnyDepthStaysInPatternUntilMatch() {
        // Pattern: **/target
        // ** should keep matching until we find "target"
        val filter = JsonStructFilter.includeAll {
            nested("**/target", JsonStructFilter.excludeAll {
                except("found")
            })
        }

        // Go very deep before finding target
        var current: JsonStructFilter = filter
        for (i in 1..10) {
            current = assertNotNull(current.nested("level$i"), "** should continue matching at level $i")
        }

        current = assertNotNull(current.nested("target"), "Should finally match 'target'")
        assertTrue(current.include("found"))
    }

    @Test
    fun testReadObjectWithPathFilter() = runTest {
        val json = """{
            "data": {
                "users": {
                    "name": "John",
                    "secret": "hidden"
                }
            },
            "meta": "kept"
        }"""

        // Only include data/users/name, exclude secret
        val filter = JsonStructFilter.includeAll {
            nested("data/users", JsonStructFilter.includeAll {
                except("secret")
            })
        }

        val reader = json.toJsonReader()
        val result = reader.readObject(filter)

        assertEquals(2, result.size)
        val data = assertNotNull(result["data"]?.jsonObject)
        val users = assertNotNull(data["users"]?.jsonObject)
        assertEquals(1, users.size)
        assertEquals("John", users["name"]?.jsonPrimitive?.content)
        assertNull(users["secret"])
    }

    @Test
    fun testAnyDepthBacktracking() {
        // Pattern: **/target/abc
        // target/xyz/target/abc should match because ** can consume "target/xyz/"
        val filter = JsonStructFilter.includeAll {
            nested("**/target/abc", JsonStructFilter.excludeAll {
                except("found")
            })
        }

        // target/xyz/target/abc
        var current: JsonStructFilter = filter
        current = assertNotNull(current.nested("target"), "Should match first 'target'")
        current = assertNotNull(current.nested("xyz"), "Should backtrack and continue with **")
        current = assertNotNull(current.nested("target"), "Should match second 'target'")
        current = assertNotNull(current.nested("abc"), "Should match 'abc' and reach terminal")

        assertTrue(current.include("found"))
        assertFalse(current.include("other"))
    }

    @Test
    fun testAnyDepthBacktrackingDeeper() {
        // Pattern: **/a/b
        // x/a/y/a/b should match (** consumes "x/a/y/")
        val filter = JsonStructFilter.includeAll {
            nested("**/a/b", JsonStructFilter.excludeAll {
                except("found")
            })
        }

        var current: JsonStructFilter = filter
        current = assertNotNull(current.nested("x"))
        current = assertNotNull(current.nested("a")) // first 'a', advance to look for 'b'
        current = assertNotNull(current.nested("y"), "Should backtrack to **")
        current = assertNotNull(current.nested("a")) // second 'a'
        current = assertNotNull(current.nested("b")) // matches 'b'

        assertTrue(current.include("found"))
    }

    @Test
    fun testWildcardMatchesArrayIndex() {
        // * should match any array index
        val filter = JsonStructFilter.includeAll {
            nested("*", JsonStructFilter.excludeAll {
                except("name")
            })
        }

        // * matches index 0
        val filter0 = assertNotNull(filter.nested("0"))
        assertTrue(filter0.include("name"))
        assertFalse(filter0.include("other"))

        // * matches index 5
        val filter5 = assertNotNull(filter.nested("5"))
        assertTrue(filter5.include("name"))
    }

    @Test
    fun testAnyDepthMatchesArrayIndex() {
        // ** should match array indices at any depth
        val filter = JsonStructFilter.includeAll {
            nested("**/target", JsonStructFilter.excludeAll {
                except("found")
            })
        }

        // Go through array indices then find target
        var current: JsonStructFilter = filter
        current = assertNotNull(current.nested("0"), "** should match index 0")
        current = assertNotNull(current.nested("1"), "** should match index 1")
        current = assertNotNull(current.nested("target"), "Should match 'target'")
        assertTrue(current.include("found"))
    }

    @Test
    fun testExactIndexInPattern() {
        // Pattern with exact index: items/0/name
        val filter = JsonStructFilter.includeAll {
            nested("items/0", JsonStructFilter.excludeAll {
                except("first")
            })
        }

        val itemsFilter = assertNotNull(filter.nested("items"))

        // Index 0 should match
        val index0 = assertNotNull(itemsFilter.nested("0"), "Should match index 0")
        assertTrue(index0.include("first"))

        // Index 1 should not match
        assertNull(itemsFilter.nested("1"), "Should not match index 1")
    }

    @Test
    fun testMixedObjectArrayPath() {
        // Pattern: */*/name (object -> array index -> key)
        val filter = JsonStructFilter.includeAll {
            nested("*/*/name", JsonStructFilter.excludeAll {
                except("found")
            })
        }

        var current: JsonStructFilter = filter
        // First * matches key "users"
        current = assertNotNull(current.nested("users"))
        // Second * matches array index 0
        current = assertNotNull(current.nested("0"), "* should match array index")
        // "name" should reach terminal
        current = assertNotNull(current.nested("name"))
        assertTrue(current.include("found"))
    }

    @Test
    fun testAnyDepthWithArrayBacktracking() {
        // Pattern: **/items/0
        // data/0/items/0 should match
        val filter = JsonStructFilter.includeAll {
            nested("**/items/0", JsonStructFilter.excludeAll {
                except("found")
            })
        }

        var current: JsonStructFilter = filter
        current = assertNotNull(current.nested("data"))
        current = assertNotNull(current.nested("0")) // array index, ** continues
        current = assertNotNull(current.nested("items"))
        current = assertNotNull(current.nested("0")) // matches the "0" in pattern
        assertTrue(current.include("found"))
    }

    @Test
    fun testTerminalAnyDepthFilter() {
        // ** as terminal should apply filter at every depth
        val filter = JsonStructFilter.includeAll {
            nested("**", JsonStructFilter.includeAll {
                except("password")
                except("secret")
            })
        }

        // At root level
        assertTrue(filter.include("name"))
        assertTrue(filter.include("password")) // included at root (before entering **)

        // Get nested filter for any key
        val level1 = assertNotNull(filter.nested("users"))
        assertTrue(level1.include("name"))
        assertFalse(level1.include("password")) // excluded by **'s filter
        assertFalse(level1.include("secret"))

        // Go deeper - should still apply same filter
        val level2 = assertNotNull(level1.nested("admin"))
        assertTrue(level2.include("email"))
        assertFalse(level2.include("password"))
        assertFalse(level2.include("secret"))

        // Even deeper
        val level3 = assertNotNull(level2.nested("settings"))
        assertFalse(level3.include("password"))
    }

    @Test
    fun testTerminalAnyDepthWithArrays() {
        // ** should work with array indices too
        val filter = JsonStructFilter.includeAll {
            nested("**", JsonStructFilter.includeAll {
                except("token")
            })
        }

        var current: JsonStructFilter = filter
        // data[0].user.token should be excluded
        current = assertNotNull(current.nested("data"))
        current = assertNotNull(current.nested("0")) // array index
        assertFalse(current.include("token"))
        assertTrue(current.include("name"))

        current = assertNotNull(current.nested("user"))
        assertFalse(current.include("token"))
    }

    @Test
    fun testReadObjectWithTerminalAnyDepth() = runTest {
        val json = """{
            "user": {
                "name": "John",
                "password": "secret123",
                "profile": {
                    "bio": "Hello",
                    "password": "nested-secret"
                }
            },
            "password": "root-password"
        }"""

        // Exclude "password" at every level
        val filter = JsonStructFilter.includeAll {
            nested("**", JsonStructFilter.includeAll {
                except("password")
            })
        }

        val reader = json.toJsonReader()
        val result = reader.readObject(filter)

        // Root level password is included (** applies after entering nested)
        assertEquals("root-password", result["password"]?.jsonPrimitive?.content)

        val user = assertNotNull(result["user"]?.jsonObject)
        assertEquals("John", user["name"]?.jsonPrimitive?.content)
        assertNull(user["password"]) // excluded by **

        val profile = assertNotNull(user["profile"]?.jsonObject)
        assertEquals("Hello", profile["bio"]?.jsonPrimitive?.content)
        assertNull(profile["password"]) // excluded by **
    }

    @Test
    fun testReadArrayWithWildcardNestedFilter() = runTest {
        val json = """[
            {"name": "John", "secret": "hidden1"},
            {"name": "Jane", "secret": "hidden2"}
        ]"""

        // For any array index, exclude "secret"
        val filter = JsonStructFilter.includeAll {
            nested("*", JsonStructFilter.includeAll {
                except("secret")
            })
        }

        val reader = json.toJsonReader()
        val result = reader.readArray(filter)

        assertEquals(2, result.size)
        val item0 = result[0].jsonObject
        assertEquals(1, item0.size)
        assertEquals("John", item0["name"]?.jsonPrimitive?.content)
        assertNull(item0["secret"])

        val item1 = result[1].jsonObject
        assertEquals(1, item1.size)
        assertEquals("Jane", item1["name"]?.jsonPrimitive?.content)
        assertNull(item1["secret"])
    }

    @Test
    fun testReadObjectWithWildcardFilter() = runTest {
        val json = """{
            "user1": {"name": "John", "password": "secret1"},
            "user2": {"name": "Jane", "password": "secret2"}
        }"""

        // For any key, exclude password
        val filter = JsonStructFilter.includeAll {
            nested("*", JsonStructFilter.includeAll {
                except("password")
            })
        }

        val reader = json.toJsonReader()
        val result = reader.readObject(filter)

        assertEquals(2, result.size)
        val user1 = assertNotNull(result["user1"]?.jsonObject)
        assertEquals(1, user1.size)
        assertEquals("John", user1["name"]?.jsonPrimitive?.content)
        assertNull(user1["password"])

        val user2 = assertNotNull(result["user2"]?.jsonObject)
        assertEquals(1, user2.size)
        assertEquals("Jane", user2["name"]?.jsonPrimitive?.content)
        assertNull(user2["password"])
    }
}
