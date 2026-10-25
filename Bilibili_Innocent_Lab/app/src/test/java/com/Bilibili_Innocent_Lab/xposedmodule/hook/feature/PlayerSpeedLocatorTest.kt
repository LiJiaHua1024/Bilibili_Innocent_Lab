package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedLocator
import org.junit.Assert.*
import org.junit.Test

class PlayerSpeedLocatorTest {
    interface State { fun getValue(): Any? }
    interface Mutable : State { fun setValue(value: Any?) }
    class Flow(private var value: Any?) : Mutable {
        override fun getValue() = value
        override fun setValue(value: Any?) { this.value = value }
    }
    class OldManager {
        @JvmField val base: Mutable = Flow(1f)
        @JvmField val temporary: Mutable = Flow(null)
        fun first() = base.getValue() as Float
        fun second() = temporary.getValue() as? Float ?: first()
    }
    class NewManager {
        @JvmField val base: Mutable = Flow(1f)
        @JvmField val temporary: Mutable = Flow(null)
        fun current() = base.getValue() as Float
    }
    class ExtraFlow {
        @JvmField val a: Mutable = Flow(1f)
        @JvmField val b: Mutable = Flow(null)
        @JvmField val c: Mutable = Flow(null)
        fun current() = a.getValue() as Float
    }
    class ExtraGetter {
        @JvmField val a: Mutable = Flow(1f)
        @JvmField val b: Mutable = Flow(null)
        fun one() = 1f
        fun two() = 1f
        fun three() = 1f
    }
    private fun locate(owner: Class<*>) = PlayerSpeedLocator.defaultSpeed(owner, Mutable::class.java, State::class.java)

    @Test fun `actual locator handles both getter shapes and exact interface fields`() {
        listOf(OldManager::class.java to 2, NewManager::class.java to 1).forEach { (owner, count) ->
            val point = requireNotNull(locate(owner))
            assertEquals(count, point.speedGetters.size)
            assertEquals(2, point.flows.size)
            val target = point.constructor.newInstance()
            assertEquals(PlayerSpeedFeatureInstaller.DefaultSpeedResult.APPLIED,
                PlayerSpeedFeatureInstaller.applyDefaultSpeed(target, point, 1.25f))
            assertTrue(point.speedGetters.all { it.invoke(target) == 1.25f })
        }
    }
    @Test fun `new flow or getter ambiguity refuses to guess`() {
        assertNull(locate(ExtraFlow::class.java))
        assertNull(locate(ExtraGetter::class.java))
        assertNull(PlayerSpeedLocator.defaultSpeed(NewManager::class.java, Flow::class.java, State::class.java))
        assertNull(PlayerSpeedLocator.defaultSpeed(NewManager::class.java, State::class.java, Mutable::class.java))
    }

    class FakeMediaResource
    class SpeedOptionModel(@JvmField val a: Float, @JvmField val b: Boolean)
    object SpeedOptionFactory {
        @JvmField val a: List<Float> = listOf(2.0f, 1.5f, 1.25f, 1.0f, 0.75f, 0.5f)
        @JvmStatic
        fun a(res: FakeMediaResource?, qn: Integer?, reverse: Boolean, mask: Int): List<SpeedOptionModel> =
            a.map { SpeedOptionModel(it, true) }
    }

    class ModernSpeedAdapter(@Suppress("UNUSED_PARAMETER") theme: Int) : View.OnClickListener {
        class Item {
            @JvmField var a: Float = 1.0f
            @JvmField var b: Boolean = false
            @JvmField var c: Boolean = true
        }
        @JvmField val c: MutableList<Item> = ArrayList()
        fun l0(currentSpeed: Float, options: List<SpeedOptionModel>) {
            c.clear()
            for (opt in options) {
                val item = Item()
                item.a = opt.a
                item.b = kotlin.math.abs(currentSpeed - opt.a) < 0.1f
                item.c = opt.b
                c.add(item)
            }
        }
        override fun onClick(v: View?) = Unit
    }

    class LegacySpeedAdapter(@Suppress("UNUSED_PARAMETER") theme: Int) : View.OnClickListener {
        class Item {
            @JvmField var a: Float = 1.0f
            @JvmField var b: Boolean = false
        }
        @JvmField val c: MutableList<Item> = ArrayList()
        @JvmField var d: FloatArray = floatArrayOf(2.0f, 1.5f, 1.25f, 1.0f, 0.75f, 0.5f)
        fun K0(currentSpeed: Float) {
            c.clear()
            for (speed in d) {
                val item = Item()
                item.a = speed
                item.b = kotlin.math.abs(currentSpeed - speed) < 0.1f
                c.add(item)
            }
        }
        override fun onClick(v: View?) = Unit
    }

    class FakeKeelPlayer(@JvmField val b: NewManager = NewManager())

    @Test fun `speed menu locator resolves modern factory and adapter and refines close speed selection`() {
        val candidates = listOf(SpeedOptionModel::class.java, SpeedOptionFactory::class.java)
        val factoryPoint = requireNotNull(
            PlayerSpeedLocator.speedMenuFactory(candidates, FakeMediaResource::class.java)
        )
        val adapterPoint = requireNotNull(
            PlayerSpeedLocator.speedMenuAdapter(
                adapterClass = ModernSpeedAdapter::class.java,
                knownFactory = factoryPoint
            )
        )
        val customMultipliers = listOf(3.0f, 1.1f, 1.05f, 1.0f, 0.5f)
        val builtOptions = requireNotNull(
            PlayerSpeedFeatureInstaller.buildOptionModels(
                factoryPoint.optionConstructor,
                factoryPoint.optionSpeedField,
                factoryPoint.optionAvailableField,
                customMultipliers
            )
        )
        assertEquals(5, builtOptions.size)
        assertTrue(
            PlayerSpeedFeatureInstaller.matchesOptionModels(
                builtOptions,
                factoryPoint.optionSpeedField,
                factoryPoint.optionAvailableField,
                customMultipliers
            )
        )

        val adapter = ModernSpeedAdapter(1)
        @Suppress("UNCHECKED_CAST")
        adapter.l0(1.05f, builtOptions as List<SpeedOptionModel>)
        // Host's < 0.1f check marks 1.1, 1.05, and 1.0 all selected before refinement:
        assertEquals(3, adapter.c.count { it.b })
        assertTrue(
            PlayerSpeedFeatureInstaller.refineAdapterItems(
                adapter,
                adapterPoint,
                1.05f,
                customMultipliers
            )
        )
        // After refinement, only 1.05f is selected:
        assertEquals(listOf(false, false, true, false, false), adapter.c.map { it.b })
        assertTrue(adapter.c.all { it.c })
    }

    @Test fun `speed menu locator resolves legacy float array adapter`() {
        val legacyPoint = requireNotNull(
            PlayerSpeedLocator.speedMenuAdapter(adapterClass = LegacySpeedAdapter::class.java)
        )
        assertNotNull(legacyPoint.speedsArrayField)
        val adapter = LegacySpeedAdapter(1)
        val custom = listOf(4.0f, 1.25f, 1.2f, 1.0f, 0.25f)
        legacyPoint.speedsArrayField!!.set(adapter, custom.toFloatArray())
        adapter.K0(1.2f)
        assertEquals(2, adapter.c.count { it.b })
        assertTrue(PlayerSpeedFeatureInstaller.refineAdapterItems(adapter, legacyPoint, 1.2f, custom))
        assertEquals(listOf(false, false, true, false, false), adapter.c.map { it.b })

    }
}
