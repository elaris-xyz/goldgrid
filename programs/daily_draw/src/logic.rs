//! Pure draw rules, kept free of accounts so they can be tested without a validator.
//! The on-chain instructions call these and nothing else decides a result.

pub const MAX_NUMBER: u8 = 85;
pub const PICKS: usize = 5;
/// Tickets a single Seeker may hold in one round, however long its streak.
pub const MAX_TICKETS_PER_ROUND: u8 = 5;
/// Consecutive rounds that earn one extra ticket.
pub const STREAK_PER_EXTRA_TICKET: u32 = 7;

/// Sorts the picks and checks they are 5 distinct numbers in 1..=85.
/// Returns None for anything else, so a malformed ticket can never be stored.
pub fn normalize_picks(mut picks: [u8; PICKS]) -> Option<[u8; PICKS]> {
    picks.sort_unstable();
    let in_range = picks.iter().all(|&n| (1..=MAX_NUMBER).contains(&n));
    let distinct = picks.windows(2).all(|w| w[0] != w[1]);
    (in_range && distinct).then_some(picks)
}

/// Five distinct winning numbers from 32 random bytes: a partial Fisher-Yates
/// shuffle of 1..=85, two bytes per draw so the modulo bias stays under 0.2%.
/// Must stay identical to `drawFive` in spikes/switchboard-devnet/spike.mjs.
pub fn draw_five(value: &[u8; 32]) -> [u8; PICKS] {
    let mut pool = [0u8; MAX_NUMBER as usize];
    for (i, slot) in pool.iter_mut().enumerate() {
        *slot = i as u8 + 1;
    }
    for i in 0..PICKS {
        let r = u16::from_be_bytes([value[2 * i], value[2 * i + 1]]) as usize;
        let j = i + r % (MAX_NUMBER as usize - i);
        pool.swap(i, j);
    }
    let mut out = [0u8; PICKS];
    out.copy_from_slice(&pool[..PICKS]);
    out.sort_unstable();
    out
}

/// How many of a ticket's numbers are among the winning numbers.
pub fn count_matches(ticket: &[u8; PICKS], winning: &[u8; PICKS]) -> u8 {
    ticket.iter().filter(|n| winning.contains(n)).count() as u8
}

/// The streak after entering `round_id`, given the last round this Seeker entered.
/// Entering again in the same round leaves the streak alone.
pub fn next_streak(last_round: Option<u64>, streak: u32, round_id: u64) -> u32 {
    match last_round {
        Some(last) if last == round_id => streak,
        Some(last) if last + 1 == round_id => streak.saturating_add(1),
        _ => 1,
    }
}

/// Tickets allowed in a round for a given streak: one, plus one per full
/// seven-round run before today, capped at five.
pub fn tickets_allowed(streak: u32) -> u8 {
    let extra = streak.saturating_sub(1) / STREAK_PER_EXTRA_TICKET;
    (1 + extra).min(MAX_TICKETS_PER_ROUND as u32) as u8
}

/// A schedule change may only move round numbering forward: the round that is
/// current under the new schedule must come after every round the old one could
/// have opened by now, so no past round's id is ever reused, reopened or
/// rewritten. Rounds that exist keep the close and draw times stored in them.
pub fn schedule_change_ok(
    old_genesis: i64,
    old_secs: i64,
    new_genesis: i64,
    new_secs: i64,
    new_entry: i64,
    now: i64,
) -> bool {
    if new_secs <= 0 || new_entry <= 0 || new_entry >= new_secs || now < new_genesis {
        return false;
    }
    let old_current = if now >= old_genesis { (now - old_genesis) / old_secs } else { -1 };
    (now - new_genesis) / new_secs > old_current
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_schedule_change_only_moves_rounds_forward() {
        let (genesis, secs, now) = (1_000, 120, 1_000 + 120 * 50 + 30); // round 50 is current
        // Ten-minute rounds whose current round is 51: allowed.
        assert!(schedule_change_ok(genesis, secs, now - 51 * 600, 600, 540, now));
        // Any numbering that lands on round 50 or earlier again: refused.
        assert!(!schedule_change_ok(genesis, secs, now - 50 * 600, 600, 540, now));
        assert!(!schedule_change_ok(genesis, secs, now - 3 * 600, 600, 540, now));
        // Nonsense schedules and a genesis in the future: refused.
        assert!(!schedule_change_ok(genesis, secs, now - 51 * 600, 600, 600, now));
        assert!(!schedule_change_ok(genesis, secs, now - 51 * 600, 0, 0, now));
        assert!(!schedule_change_ok(genesis, secs, now + 1, 600, 540, now));
    }

    #[test]
    fn picks_must_be_five_distinct_numbers_in_range() {
        assert_eq!(normalize_picks([5, 1, 85, 40, 2]), Some([1, 2, 5, 40, 85]));
        assert_eq!(normalize_picks([0, 1, 2, 3, 4]), None);
        assert_eq!(normalize_picks([1, 2, 3, 4, 86]), None);
        assert_eq!(normalize_picks([7, 7, 1, 2, 3]), None);
    }

    #[test]
    fn draw_matches_the_devnet_spike() {
        // Revealed on devnet on 2026-09-25; the spike printed "16 31 70 80 83".
        let hex = "68b5b4729246ddeae0a3e3d93e97148699b7b890578ce41e381a29afdac428f4";
        let mut value = [0u8; 32];
        for i in 0..32 {
            value[i] = u8::from_str_radix(&hex[2 * i..2 * i + 2], 16).unwrap();
        }
        assert_eq!(draw_five(&value), [16, 31, 70, 80, 83]);
    }

    #[test]
    fn draw_is_always_a_valid_ticket() {
        for seed in 0u32..2000 {
            let mut value = [0u8; 32];
            for (i, b) in value.iter_mut().enumerate() {
                *b = (seed.wrapping_mul(2654435761).rotate_left(i as u32) >> 8) as u8;
            }
            let drawn = draw_five(&value);
            assert_eq!(normalize_picks(drawn), Some(drawn), "seed {seed}");
        }
    }

    #[test]
    fn matches_are_counted_as_a_set() {
        assert_eq!(count_matches(&[1, 2, 3, 4, 5], &[1, 2, 3, 4, 5]), 5);
        assert_eq!(count_matches(&[1, 2, 3, 4, 5], &[5, 10, 20, 30, 40]), 1);
        assert_eq!(count_matches(&[1, 2, 3, 4, 5], &[6, 7, 8, 9, 10]), 0);
    }

    #[test]
    fn streak_continues_only_on_the_next_round() {
        assert_eq!(next_streak(None, 0, 10), 1);
        assert_eq!(next_streak(Some(9), 4, 10), 5);
        assert_eq!(next_streak(Some(10), 5, 10), 5);
        assert_eq!(next_streak(Some(7), 9, 10), 1);
    }

    #[test]
    fn a_week_of_rounds_earns_a_ticket_up_to_five() {
        assert_eq!(tickets_allowed(1), 1);
        assert_eq!(tickets_allowed(7), 1);
        assert_eq!(tickets_allowed(8), 2);
        assert_eq!(tickets_allowed(15), 3);
        assert_eq!(tickets_allowed(1000), 5);
    }
}
