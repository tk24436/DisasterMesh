package com.example.disastermesh.ai

class FirstAidKnowledgeBase {

    data class FirstAidEntry(
        val title: String,
        val emoji: String,
        val summary: String,
        val steps: List<String>,
        val doNot: List<String>,
        val seekHelp: String,
        val keywords: List<String>
    )

    private val entries = listOf(
        FirstAidEntry(
            title = "CPR (Cardiopulmonary Resuscitation)",
            emoji = "❤️",
            summary = "For unresponsive person not breathing normally. Act within 4 minutes.",
            steps = listOf(
                "1. Check responsiveness — tap shoulders, shout 'Are you okay?'",
                "2. Call for help — ask someone to call 102/108 (ambulance)",
                "3. Place person on firm, flat surface on their back",
                "4. Place heel of one hand on centre of chest (between nipples)",
                "5. Place other hand on top, interlock fingers",
                "6. Push hard and fast — 5-6 cm deep, 100-120 compressions/minute",
                "7. After 30 compressions, tilt head back, lift chin, give 2 breaths",
                "8. Continue 30:2 cycle until help arrives or person recovers",
                "9. If you're untrained, do hands-only CPR (compressions only)"
            ),
            doNot = listOf(
                "Don't stop compressions for more than 10 seconds",
                "Don't push on ribs or stomach",
                "Don't give up — continue until professionals arrive"
            ),
            seekHelp = "Call 102 or 108 immediately. CPR is a bridge until ambulance arrives.",
            keywords = listOf("cpr", "not breathing", "cardiac", "heart stopped", "heart attack",
                "unconscious", "unresponsive", "no pulse", "chest compression", "resuscitation",
                "dil", "saans nahi", "breathing stopped")
        ),

        FirstAidEntry(
            title = "Severe Bleeding Control",
            emoji = "🩸",
            summary = "Stop life-threatening bleeding immediately. Severe blood loss kills in minutes.",
            steps = listOf(
                "1. Ensure your own safety first — wear gloves if available",
                "2. Apply DIRECT PRESSURE with a clean cloth/pad on the wound",
                "3. Press firmly and continuously — do not lift to check",
                "4. If blood soaks through, add more cloth ON TOP (don't remove first layer)",
                "5. Elevate the injured limb above heart level if possible",
                "6. For limb bleeding that won't stop — apply a tourniquet 5-7 cm above the wound",
                "7. Note the time tourniquet was applied",
                "8. Keep the person warm and lying down (prevents shock)",
                "9. If wound is on torso/neck — pack wound tightly with cloth and maintain pressure"
            ),
            doNot = listOf(
                "Don't remove the first cloth — add on top",
                "Don't use a tourniquet on neck or torso",
                "Don't give the person water if they might need surgery"
            ),
            seekHelp = "Call 102/108. Severe bleeding needs hospital treatment within the golden hour.",
            keywords = listOf("bleeding", "blood", "cut", "wound", "laceration", "gash",
                "hemorrhage", "tourniquet", "khoon", "kata", "chot", "injury", "deep cut")
        ),

        FirstAidEntry(
            title = "Burns Treatment",
            emoji = "🔥",
            summary = "Cool the burn immediately. Classification: 1st (red), 2nd (blisters), 3rd (charred/white).",
            steps = listOf(
                "1. Remove from heat source immediately",
                "2. Cool the burn under clean, running water for 20 MINUTES minimum",
                "3. Remove clothing/jewelry near the burn (unless stuck to skin)",
                "4. Cover with clean, non-fluffy material (cling film works well)",
                "5. For chemical burns — flush with large amounts of water for 20+ minutes",
                "6. For electrical burns — ensure power source is disconnected first",
                "7. Give small sips of water if person is conscious",
                "8. Keep the person warm (burns cause heat loss)"
            ),
            doNot = listOf(
                "Don't use ice — it worsens tissue damage",
                "Don't apply butter, toothpaste, oil, or any home remedy",
                "Don't break blisters",
                "Don't remove clothing stuck to burns",
                "Don't use cotton wool (fibres stick to burns)"
            ),
            seekHelp = "Hospital for: burns larger than palm size, burns on face/hands/joints, chemical/electrical burns, 3rd degree burns.",
            keywords = listOf("burn", "fire", "scald", "hot water", "chemical burn", "flame",
                "electrical burn", "jalana", "jala", "aag", "heat injury")
        ),

        FirstAidEntry(
            title = "Fractures & Bone Injuries",
            emoji = "🦴",
            summary = "Immobilize the injury. Do NOT try to realign the bone.",
            steps = listOf(
                "1. Keep the injured person still — don't move them unless in danger",
                "2. Immobilize the injured area — use a splint or padded rigid object",
                "3. Splint should extend beyond joints above and below the fracture",
                "4. Tie splint gently — tight enough to hold, loose enough for circulation",
                "5. Apply ice wrapped in cloth for 20 minutes to reduce swelling",
                "6. Elevate the injured limb if possible",
                "7. For suspected spine/neck injury — DO NOT MOVE the person at all",
                "8. Keep the person calm and warm"
            ),
            doNot = listOf(
                "Don't try to push bone back in",
                "Don't straighten a deformed limb",
                "Don't move person with suspected spinal injury",
                "Don't apply ice directly on skin"
            ),
            seekHelp = "All fractures need X-ray and professional treatment. Call 102/108 for spinal injuries.",
            keywords = listOf("fracture", "broken bone", "break", "sprain", "dislocation",
                "swelling", "deformity", "haddi tooti", "toot gaya", "bone", "joint injury",
                "fall", "fell down")
        ),

        FirstAidEntry(
            title = "Snake Bite (India-Specific)",
            emoji = "🐍",
            summary = "India's Big Four: Cobra, Krait, Russell's Viper, Saw-scaled Viper. Anti-venom saves lives.",
            steps = listOf(
                "1. KEEP CALM — panic increases heart rate and spreads venom faster",
                "2. Move away from the snake — do not try to catch or kill it",
                "3. Immobilize the bitten limb — keep it BELOW heart level",
                "4. Remove rings, watches, tight clothing near the bite",
                "5. Apply a pressure bandage — firm but not cutting off circulation",
                "6. Mark the edge of swelling with a pen and note the time",
                "7. Keep the person still and calm",
                "8. If possible, note the snake's appearance (color, pattern, head shape)",
                "9. Transport to nearest hospital WITH anti-venom facility immediately"
            ),
            doNot = listOf(
                "Don't cut the wound or try to suck out venom",
                "Don't apply a tourniquet (blocks blood flow, causes tissue death)",
                "Don't apply ice, heat, or electric shock",
                "Don't give alcohol or aspirin",
                "Don't waste time with traditional remedies (jhad-phoonk)"
            ),
            seekHelp = "IMMEDIATE hospital visit required. India anti-venom helpline: Contact nearest government hospital. Most district hospitals stock polyvalent anti-venom.",
            keywords = listOf("snake", "bite", "venom", "cobra", "krait", "viper", "saanp",
                "sapera", "poison", "nag", "snake bite", "venomous")
        ),

        FirstAidEntry(
            title = "Earthquake Safety",
            emoji = "🏚️",
            summary = "Drop, Cover, Hold On. Most injuries are from falling objects, not the ground shaking.",
            steps = listOf(
                "DURING EARTHQUAKE:",
                "1. DROP to hands and knees immediately",
                "2. Take COVER under sturdy desk/table, protect head and neck",
                "3. HOLD ON to shelter until shaking stops",
                "4. If no table — go to interior wall, crouch, protect head with arms",
                "5. If outdoors — move to open area away from buildings/wires/trees",
                "6. If driving — stop safely, stay inside car, avoid bridges/overpasses",
                "",
                "AFTER EARTHQUAKE:",
                "7. Check yourself and others for injuries",
                "8. Expect aftershocks — take cover again if shaking resumes",
                "9. If trapped — tap on pipe/wall to signal rescuers, don't shout (save energy)",
                "10. Check for gas leaks, damaged wiring, structural damage",
                "11. Move to open area if building is damaged",
                "12. Listen to radio/authorities for updates"
            ),
            doNot = listOf(
                "Don't run outside during shaking",
                "Don't stand in doorway (outdated advice)",
                "Don't use elevators",
                "Don't light matches if you smell gas",
                "Don't go back into damaged buildings"
            ),
            seekHelp = "NDRF (National Disaster Response Force): 011-24363260. Call 112 for emergency.",
            keywords = listOf("earthquake", "quake", "tremor", "shaking", "building collapse",
                "bhukamp", "collapsed", "aftershock", "seismic", "rubble")
        ),

        FirstAidEntry(
            title = "Flood Safety",
            emoji = "🌊",
            summary = "Move to higher ground immediately. Just 15 cm of fast-moving water can knock you down.",
            steps = listOf(
                "BEFORE/DURING FLOOD:",
                "1. Move to higher ground immediately — don't wait",
                "2. Do NOT walk, swim, or drive through flood water",
                "3. Stay off bridges over fast-moving water",
                "4. Disconnect electrical appliances if safe to do so",
                "5. Store drinking water — flood water is contaminated",
                "",
                "IF TRAPPED IN FLOOD:",
                "6. Go to highest point of building (roof if necessary)",
                "7. Signal for help — use bright cloth, torch, phone",
                "8. Don't enter attic unless there's roof access (risk of being trapped)",
                "",
                "AFTER FLOOD:",
                "9. Don't drink tap water until authorities confirm it's safe",
                "10. Watch for snakes and insects displaced by water",
                "11. Avoid walking in standing water (hidden dangers, disease)",
                "12. Clean and disinfect everything that got wet"
            ),
            doNot = listOf(
                "Don't drive through flooded roads (2 feet of water can float a car)",
                "Don't touch electrical equipment if wet/standing in water",
                "Don't eat food that has been in contact with flood water"
            ),
            seekHelp = "NDRF: 011-24363260. State Disaster helpline. Call 112 for emergency.",
            keywords = listOf("flood", "water", "drowning", "rain", "baarish", "paani",
                "submerged", "waterlogged", "dam", "overflow", "tsunami")
        ),

        FirstAidEntry(
            title = "Heat Stroke / Heat Exhaustion",
            emoji = "☀️",
            summary = "Heat stroke is a medical EMERGENCY — body temp above 40°C. Common in Indian summers.",
            steps = listOf(
                "HEAT EXHAUSTION (less severe):",
                "1. Move person to cool, shaded area immediately",
                "2. Lay them down, elevate legs slightly",
                "3. Remove excess clothing",
                "4. Give cool water in small sips (if conscious)",
                "5. Apply wet cloths/spray water on skin",
                "6. Fan the person continuously",
                "",
                "HEAT STROKE (EMERGENCY — hot dry skin, confusion, unconscious):",
                "7. Call 102/108 IMMEDIATELY",
                "8. Cool the person aggressively — immerse in cool water if possible",
                "9. Apply ice packs to neck, armpits, groin",
                "10. Fan continuously while wetting skin",
                "11. Do NOT give water if unconscious or confused",
                "12. Monitor breathing — start CPR if needed"
            ),
            doNot = listOf(
                "Don't give very cold water to drink (causes cramping)",
                "Don't give alcohol or caffeine",
                "Don't leave the person alone",
                "Don't delay cooling — every minute matters"
            ),
            seekHelp = "Heat stroke needs IMMEDIATE hospital treatment. Call 102/108. Can be fatal if untreated.",
            keywords = listOf("heat", "stroke", "sunstroke", "hot", "loo", "garmi", "sun",
                "dehydration", "summer", "sweating", "faint", "dizziness", "loo lagna",
                "temperature")
        ),

        FirstAidEntry(
            title = "Choking",
            emoji = "😫",
            summary = "Blocked airway. Act immediately — brain damage starts in 4 minutes without oxygen.",
            steps = listOf(
                "FOR CONSCIOUS ADULT/CHILD (over 1 year):",
                "1. Encourage coughing — if they can cough, let them",
                "2. If coughing fails — give 5 BACK BLOWS (between shoulder blades, with heel of hand)",
                "3. If back blows fail — give 5 ABDOMINAL THRUSTS (Heimlich maneuver)",
                "   - Stand behind, wrap arms around waist",
                "   - Make fist, place thumb side just above navel",
                "   - Grasp fist with other hand, thrust inward and upward",
                "4. Alternate 5 back blows → 5 thrusts until cleared",
                "",
                "FOR INFANT (under 1 year):",
                "5. Lay face down on your forearm, support head",
                "6. Give 5 back blows between shoulder blades",
                "7. Turn over, give 5 chest thrusts (2 fingers, centre of chest)",
                "8. Alternate until object clears or infant becomes unconscious",
                "",
                "IF PERSON BECOMES UNCONSCIOUS:",
                "9. Lower to ground, call 102/108, start CPR"
            ),
            doNot = listOf(
                "Don't do blind finger sweeps (can push object deeper)",
                "Don't slap on back if person can still cough",
                "Don't do abdominal thrusts on infants or pregnant women"
            ),
            seekHelp = "Call 102/108 if choking is not resolved quickly. Even after clearing, see a doctor.",
            keywords = listOf("choking", "choke", "airway", "can't breathe", "swallowed",
                "stuck throat", "gala", "saans", "breathing difficulty", "object stuck")
        ),

        FirstAidEntry(
            title = "Drowning / Near-Drowning",
            emoji = "🏊",
            summary = "Remove from water safely. Start CPR immediately if not breathing.",
            steps = listOf(
                "1. Ensure YOUR safety first — don't jump in unless trained",
                "2. Reach: extend pole/rope/clothing from shore if possible",
                "3. Throw: toss flotation device (bottle, ball, cooler)",
                "4. Row: use boat if available",
                "5. Once out of water — check breathing immediately",
                "6. If not breathing — start CPR (30 compressions : 2 breaths)",
                "7. If breathing — place in recovery position (on side)",
                "8. Remove wet clothing, keep warm with blankets/dry clothes",
                "9. Even if person seems fine — get medical evaluation (secondary drowning risk)"
            ),
            doNot = listOf(
                "Don't attempt rescue beyond your ability (you can drown too)",
                "Don't assume the person is fine just because they're breathing",
                "Don't leave recovered person alone for several hours"
            ),
            seekHelp = "ALL drowning/near-drowning needs hospital evaluation. Call 102/108.",
            keywords = listOf("drowning", "water", "pool", "river", "swim", "submerged",
                "nadi", "talab", "doobna", "paani mein", "underwater")
        ),

        FirstAidEntry(
            title = "Building Collapse / Trapped Under Rubble",
            emoji = "🏗️",
            summary = "If trapped: protect airway, conserve energy, signal rescuers. Don't panic.",
            steps = listOf(
                "IF YOU ARE TRAPPED:",
                "1. Cover mouth and nose with cloth (dust protection)",
                "2. Don't move more than necessary — avoid disturbing unstable debris",
                "3. Tap on pipe, wall, or hard surface to signal rescuers (3 taps, pause, repeat)",
                "4. Don't shout — save energy, inhale less dust",
                "5. Use phone flashlight or whistle if available",
                "6. If you can see daylight/opening — move toward it carefully",
                "",
                "IF YOU ARE RESCUING:",
                "7. Call NDRF: 011-24363260 and local fire brigade: 101",
                "8. Listen for tapping, voices, phone sounds",
                "9. Don't move heavy debris without training (can cause secondary collapse)",
                "10. Mark locations where you hear sounds",
                "11. Provide water/food through small openings if possible",
                "12. Keep talking to trapped person — maintain hope and consciousness"
            ),
            doNot = listOf(
                "Don't light fires near rubble (gas leaks possible)",
                "Don't use heavy machinery without expert guidance",
                "Don't enter unstable structures"
            ),
            seekHelp = "NDRF: 011-24363260. Fire Brigade: 101. Emergency: 112. SDRF (State) varies by state.",
            keywords = listOf("collapse", "rubble", "trapped", "building fell", "debris",
                "buried", "stuck", "rescue", "crush", "imarat", "dhansa", "gir gaya")
        ),

        FirstAidEntry(
            title = "India Emergency Contacts",
            emoji = "📞",
            summary = "Key emergency numbers for India. Save these offline.",
            steps = listOf(
                "UNIVERSAL EMERGENCY: 112 (works from any phone, even without SIM)",
                "AMBULANCE: 102 or 108",
                "POLICE: 100",
                "FIRE BRIGADE: 101",
                "WOMEN HELPLINE: 1091 or 181",
                "CHILD HELPLINE: 1098",
                "NDRF (National Disaster Response Force): 011-24363260",
                "POISON HELPLINE: 1800-11-6117 (AIIMS)",
                "RAILWAY EMERGENCY: 139",
                "ROAD ACCIDENT: 1073",
                "",
                "STATE DISASTER HELPLINES:",
                "Delhi: 1077",
                "UP: 1070",
                "Maharashtra: 022-22694725",
                "Tamil Nadu: 1070",
                "Karnataka: 1070",
                "Kerala: 1077",
                "",
                "TIPS:",
                "- 112 works even without network signal (connects to any available tower)",
                "- Share your GPS coordinates if possible",
                "- Stay on the line until operator confirms help is dispatched"
            ),
            doNot = listOf(),
            seekHelp = "When in doubt, call 112. It routes to appropriate emergency service.",
            keywords = listOf("emergency", "phone", "number", "call", "helpline", "contact",
                "ambulance", "police", "fire", "ndrf", "hospital", "madad", "help number",
                "rescue team", "112", "102", "108", "100", "101")
        ),

        FirstAidEntry(
            title = "Scorpion Sting",
            emoji = "🦂",
            summary = "Common in rural India. Most stings are painful but not fatal. Watch for severe reactions.",
            steps = listOf(
                "1. Clean the sting area with soap and water",
                "2. Apply cold compress (ice wrapped in cloth) for 10-15 minutes",
                "3. Take over-the-counter pain reliever if available (paracetamol)",
                "4. Keep the affected area immobilized and below heart level",
                "5. Monitor for severe symptoms:",
                "   - Difficulty breathing",
                "   - Muscle twitching/spasms",
                "   - Excessive sweating/drooling",
                "   - Blurred vision",
                "   - Rapid heart rate",
                "6. If child under 6 — treat as EMERGENCY regardless of symptoms"
            ),
            doNot = listOf(
                "Don't cut the wound or try to suck out venom",
                "Don't apply tourniquet",
                "Don't apply traditional remedies"
            ),
            seekHelp = "Hospital visit for children, elderly, or if severe symptoms appear. Call 102/108.",
            keywords = listOf("scorpion", "sting", "bichchoo", "bichhoo", "venom",
                "scorpion bite")
        ),

        FirstAidEntry(
            title = "Allergic Reaction / Anaphylaxis",
            emoji = "⚠️",
            summary = "Anaphylaxis is life-threatening. Signs: swelling, difficulty breathing, rapid pulse, rash.",
            steps = listOf(
                "MILD ALLERGIC REACTION:",
                "1. Remove/avoid the allergen if known",
                "2. Give antihistamine (cetirizine/levocetirizine) if available",
                "3. Apply cold compress to itchy/swollen areas",
                "",
                "SEVERE REACTION (ANAPHYLAXIS) — EMERGENCY:",
                "4. Call 102/108 IMMEDIATELY",
                "5. If person has epinephrine auto-injector (EpiPen) — help them use it",
                "   - Inject into outer thigh, through clothing if needed",
                "6. Lay person down, elevate legs (unless breathing difficulty — then sit up)",
                "7. Loosen tight clothing",
                "8. If breathing stops — start CPR",
                "9. Stay with the person until ambulance arrives",
                "10. Note what caused the reaction and when it started"
            ),
            doNot = listOf(
                "Don't give oral medication if person has trouble swallowing",
                "Don't make them stand up (blood pressure may drop)",
                "Don't delay calling for help"
            ),
            seekHelp = "Anaphylaxis is always a 102/108 emergency. Even if symptoms improve, hospital monitoring is needed.",
            keywords = listOf("allergy", "allergic", "anaphylaxis", "swelling", "rash",
                "hives", "breathing difficulty", "epipen", "reaction", "itching", "sujan")
        )
    )

    fun search(query: String): FirstAidEntry? {
        val queryLower = query.lowercase().trim()

        if (queryLower.isBlank()) return null

        // Exact keyword match first
        for (entry in entries) {
            for (keyword in entry.keywords) {
                if (queryLower.contains(keyword) || keyword.contains(queryLower)) {
                    return entry
                }
            }
        }

        // Word-level matching
        val queryWords = queryLower.split("\\s+".toRegex())
        var bestMatch: FirstAidEntry? = null
        var bestScore = 0

        for (entry in entries) {
            var score = 0
            for (word in queryWords) {
                if (word.length < 3) continue
                for (keyword in entry.keywords) {
                    if (keyword.contains(word) || word.contains(keyword)) {
                        score++
                    }
                }
                if (entry.title.lowercase().contains(word)) score += 2
                if (entry.summary.lowercase().contains(word)) score++
            }
            if (score > bestScore) {
                bestScore = score
                bestMatch = entry
            }
        }

        return bestMatch
    }

    fun formatResponse(entry: FirstAidEntry): String {
        val sb = StringBuilder()
        sb.appendLine("${entry.emoji} ${entry.title}")
        sb.appendLine()
        sb.appendLine(entry.summary)
        sb.appendLine()
        sb.appendLine("── Steps ──")
        entry.steps.forEach { step ->
            sb.appendLine(step)
        }
        if (entry.doNot.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("── Do NOT ──")
            entry.doNot.forEach { item ->
                sb.appendLine("✖ $item")
            }
        }
        sb.appendLine()
        sb.appendLine("── When to Seek Help ──")
        sb.appendLine(entry.seekHelp)
        return sb.toString()
    }

    fun getQuickActions(): List<Pair<String, String>> {
        return listOf(
            "CPR" to "How to perform CPR",
            "Bleeding" to "How to stop severe bleeding",
            "Burns" to "How to treat burns",
            "Snake Bite" to "Snake bite first aid India",
            "Earthquake" to "Earthquake safety steps",
            "Choking" to "Choking first aid",
            "Heat Stroke" to "Heat stroke treatment",
            "Contacts" to "India emergency contacts"
        )
    }

    fun getAllTopics(): List<String> {
        return entries.map { "${it.emoji} ${it.title}" }
    }
}
