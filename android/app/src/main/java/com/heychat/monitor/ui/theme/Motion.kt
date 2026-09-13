package com.heychat.monitor.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.ui.unit.IntOffset

/**
 * Expressive 动效基调：屏幕过渡与状态变化统一使用带轻微回弹的弹簧。
 * material3 1.4.0 的 MotionScheme.expressive() 是 internal，因此这里给出等价弹簧，
 * 主题侧则通过 MaterialExpressiveTheme 使用 Expressive 默认动效。
 */
object Motion {
    /** 位移弹簧：末端轻微回弹。 */
    fun offsetSpring() = spring<IntOffset>(
        dampingRatio = 0.76f,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** 状态变化弹簧（尺寸、透明度等）。 */
    fun <T : Any> bounce(
        dampingRatio: Float = 0.52f,
        stiffness: Float = Spring.StiffnessLow,
    ) = spring<T>(dampingRatio = dampingRatio, stiffness = stiffness)
}

/** 前进：新页面从右侧进入，旧页面向左离开。 */
fun enterFromRight(): EnterTransition = slideInHorizontally(Motion.offsetSpring()) { it }

fun exitToLeft(): ExitTransition = slideOutHorizontally(Motion.offsetSpring()) { -it }

/** 返回：反向播放进入过渡。 */
fun enterFromLeft(): EnterTransition = slideInHorizontally(Motion.offsetSpring()) { -it }

fun exitToRight(): ExitTransition = slideOutHorizontally(Motion.offsetSpring()) { it }

/** 卡片/浮层内容出现：轻微放大 + 淡入。 */
fun popEnter(): EnterTransition =
    scaleIn(initialScale = 0.92f, animationSpec = Motion.bounce<Float>()) + fadeIn(tween(180))

fun popExit(): ExitTransition =
    scaleOut(targetScale = 0.96f, animationSpec = tween(140)) + fadeOut(tween(140))
