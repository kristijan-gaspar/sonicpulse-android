package hr.sonicpulse.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Named shapes per component kind — not a single M3 [androidx.compose.material3.Shapes] scheme, since each component kind has its own corner radius. */
object AppShapes {
    val Card = RoundedCornerShape(16.dp)
    val ChipOrBadge = RoundedCornerShape(10.dp)
    val Button = RoundedCornerShape(16.dp)
    val BottomSheet = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    val IconContainer = RoundedCornerShape(12.dp)
    val Pill = RoundedCornerShape(20.dp)
}
