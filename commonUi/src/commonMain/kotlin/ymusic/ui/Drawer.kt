package ymusic.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import ymusic.ui.resources.Res
import ymusic.ui.resources.app_name
import ymusic.ui.resources.badge_premium
import ymusic.ui.resources.ymusic_logo

/** Premium brand marks stay green (design/final/drawer.html); the ACTIVE item is accent. */
private val BrandGreen = DesignColors.BrandGreen
private val BrandGreenDark = Color(0xFF15803D)
private val BrandGreenBg = Color(0xFFEAFAF0)
private val BrandGreenBorder = Color(0xFFCDEED9)
private val ActiveAccentDark = Color(0xFF8A3D0F)
private val ActiveAccentBg = Color(0xFFFBE9DD)

/** The navigation drawer (design/final/drawer.html): brand header + three nav sections. */
@Composable
fun AppDrawer(
    section: AppSection,
    appVersion: String?,
    onSelect: (AppSection) -> Unit,
) {
    ModalDrawerSheet(
        modifier = Modifier.width(300.dp),
        drawerContainerColor = Color.White,
        drawerShape = RoundedCornerShape(0.dp),
    ) {
        Column(Modifier.fillMaxHeight()) {
            DrawerHeader(appVersion)
            HorizontalDivider(color = Color(0xFFEEEEEE))
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                AppSection.entries.groupBy { it.group }.entries.forEachIndexed { index, (_, sections) ->
                    if (index > 0) HorizontalDivider(color = Color(0xFFF0F0F0), modifier = Modifier.padding(vertical = 4.dp))
                    sections.forEach { s ->
                        DrawerItem(s, active = s == section) { onSelect(s) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerHeader(appVersion: String?) {
    Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 13.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(
                painterResource(Res.drawable.ymusic_logo),
                contentDescription = stringResource(Res.string.app_name),
                modifier = Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)),
            )
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(Res.string.app_name),
                        style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF1A1A1A)),
                    )
                    Text(
                        stringResource(Res.string.badge_premium),
                        style = TextStyle(
                            fontSize = 9.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.54.sp,
                            color = BrandGreenDark,
                        ),
                        modifier = Modifier
                            .background(BrandGreenBg, RoundedCornerShape(5.dp))
                            .border(1.dp, BrandGreenBorder, RoundedCornerShape(5.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                appVersion?.let {
                    Text(
                        it,
                        style = TextStyle(fontSize = 11.sp, color = DesignColors.InkFaint),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerItem(section: AppSection, active: Boolean, onClick: () -> Unit) {
    val activeBar = DesignColors.Accent
    val activeBg = ActiveAccentBg
    val activeDark = ActiveAccentDark
    Box(Modifier.fillMaxWidth().clickable(onClick = onClick).background(if (active) activeBg else Color.Transparent)) {
        if (active) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(vertical = 8.dp)
                    .size(width = 3.dp, height = 33.dp)
                    .background(activeBar, RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp)),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                section.icon,
                null,
                Modifier.size(23.dp),
                tint = when {
                    active -> activeBar
                    else -> Color(0xFF3F3F3F)
                },
            )
            val labelStyle = when {
                active -> TextStyle(fontSize = 14.5.sp, fontWeight = FontWeight.ExtraBold, color = activeDark)
                else -> TextStyle(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF2C2C2C))
            }
            Text(stringResource(section.label), style = labelStyle, modifier = Modifier.weight(1f))
        }
    }
}
