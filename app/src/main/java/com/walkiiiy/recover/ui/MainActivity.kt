package com.walkiiiy.recover.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.walkiiiy.recover.R
import com.walkiiiy.recover.databinding.ActivityMainBinding
import com.walkiiiy.recover.ui.exercise.ExerciseListFragment
import com.walkiiiy.recover.ui.history.HistoryFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            showFragment(item.itemId)
            true
        }

        if (savedInstanceState == null) {
            binding.bottomNavigation.selectedItemId = R.id.navigation_exercises
        }
    }

    private fun showFragment(itemId: Int) {
        val fragmentManager = supportFragmentManager
        
        // 使用 commitAllowingStateLoss() 代替 commit()，防止在 onSaveInstanceState 后崩溃
        val transaction = fragmentManager.beginTransaction()

        NavigationItem.entries.forEach { navItem ->
            fragmentManager.findFragmentByTag(navItem.tag)?.let { transaction.hide(it) }
        }

        val navigationItem = NavigationItem.fromMenuId(itemId) ?: return
        var fragment = fragmentManager.findFragmentByTag(navigationItem.tag)
        if (fragment == null) {
            fragment = when (navigationItem) {
                NavigationItem.EXERCISES -> ExerciseListFragment.newInstance()
                NavigationItem.HISTORY -> HistoryFragment.newInstance()
            }
            transaction.add(R.id.nav_host_container, fragment, navigationItem.tag)
        } else {
            transaction.show(fragment)
        }

        // 使用 commitAllowingStateLoss 避免状态丢失异常
        transaction.commitAllowingStateLoss()
        binding.toolbar.title = navigationItem.titleResId.let(::getString)
    }

    private enum class NavigationItem(val menuId: Int, val tag: String, val titleResId: Int) {
        EXERCISES(
            menuId = R.id.navigation_exercises,
            tag = "tag_exercises",
            titleResId = R.string.exercise_selection_title
        ),
        HISTORY(
            menuId = R.id.navigation_history,
            tag = "tag_history",
            titleResId = R.string.history_title
        );

        companion object {
            val entries: List<NavigationItem> = values().toList()

            fun fromMenuId(menuId: Int): NavigationItem? =
                entries.firstOrNull { it.menuId == menuId }
        }
    }
}
