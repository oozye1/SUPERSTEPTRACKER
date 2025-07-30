package co.uk.doverguitarteacher.superstepstracker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Immutable
private data class Challenge(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val progress: Float
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChallengesScreen(padding: PaddingValues) {
    val challenges = remember {
        listOf(
            Challenge("Weekend Warrior", "Get 25,000 steps over a weekend.", Icons.Default.Star, 0.75f),
            Challenge("Daily Streak", "Hit your daily goal 7 days in a row.", Icons.Default.Whatshot, 0.4f),
            Challenge("Mountain Climber", "A tough challenge for the most dedicated.", Icons.Default.Terrain, 0.1f)
        )
    }
    Scaffold(
        modifier = Modifier.padding(padding),
        topBar = {
            TopAppBar(
                title = { Text("Challenges") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(challenges) { challenge ->
                ChallengeCard(challenge)
            }
        }
    }
}

@Composable
private fun ChallengeCard(challenge: Challenge) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                challenge.icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(challenge.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(challenge.description, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { challenge.progress },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
