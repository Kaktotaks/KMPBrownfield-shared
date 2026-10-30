package com.example.tasks.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class TodoMapperTest {

    @Test
    fun mappingFollowsSpec() {
        assertEquals(
            Task(id = 1, title = "delectus", notes = "", priority = Priority.MEDIUM, dueDate = day(-2), isDone = true),
            TodoMapper.map(id = 1, title = "delectus", completed = true, today = TODAY),
        )
    }

    @Test
    fun priorityAndDueDateCycle() {
        val t3 = TodoMapper.map(3, "x", false, TODAY)
        assertEquals(Priority.LOW, t3.priority)
        assertEquals(TODAY, t3.dueDate)
        val t14 = TodoMapper.map(14, "x", false, TODAY)
        assertEquals(Priority.HIGH, t14.priority)
        assertEquals(day(-3), t14.dueDate)
    }
}
