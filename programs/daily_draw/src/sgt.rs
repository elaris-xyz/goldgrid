//! Seeker Genesis Token check without pulling in spl-token-2022: a genuine SGT is a
//! Token-2022 mint whose TokenGroupMember extension names the SGT group. Only the
//! group's update authority can add a member, so the group field cannot be forged;
//! a metadata pointer can (anyone may point a new mint at the SGT metadata).

use anchor_lang::prelude::*;

/// Mint layout: 82-byte base, zero padding up to the 165-byte account size, then
/// one account-type byte, then type-length-value extensions.
const ACCOUNT_TYPE_OFFSET: usize = 165;
const ACCOUNT_TYPE_MINT: u8 = 1;
const EXTENSION_UNINITIALIZED: u16 = 0;
const EXTENSION_TOKEN_GROUP_MEMBER: u16 = 23;
/// TokenGroupMember: mint (32) | group (32) | member_number (8).
const GROUP_MEMBER_LEN: usize = 72;

/// The group a Token-2022 mint belongs to, if it carries a TokenGroupMember
/// extension that names this very mint.
pub fn member_group(mint_key: &Pubkey, mint_data: &[u8]) -> Option<Pubkey> {
    if mint_data.get(ACCOUNT_TYPE_OFFSET) != Some(&ACCOUNT_TYPE_MINT) {
        return None;
    }
    let mut at = ACCOUNT_TYPE_OFFSET + 1;
    while at + 4 <= mint_data.len() {
        let kind = u16::from_le_bytes([mint_data[at], mint_data[at + 1]]);
        let len = u16::from_le_bytes([mint_data[at + 2], mint_data[at + 3]]) as usize;
        let value = mint_data.get(at + 4..at + 4 + len)?;
        if kind == EXTENSION_UNINITIALIZED {
            return None;
        }
        if kind == EXTENSION_TOKEN_GROUP_MEMBER && len == GROUP_MEMBER_LEN {
            let member_mint = Pubkey::try_from(&value[..32]).ok()?;
            let group = Pubkey::try_from(&value[32..64]).ok()?;
            return (member_mint == *mint_key).then_some(group);
        }
        at += 4 + len;
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    fn mint_with(extensions: &[(u16, Vec<u8>)]) -> Vec<u8> {
        let mut data = vec![0u8; ACCOUNT_TYPE_OFFSET];
        data.push(ACCOUNT_TYPE_MINT);
        for (kind, value) in extensions {
            data.extend_from_slice(&kind.to_le_bytes());
            data.extend_from_slice(&(value.len() as u16).to_le_bytes());
            data.extend_from_slice(value);
        }
        data
    }

    fn member(mint: &Pubkey, group: &Pubkey) -> Vec<u8> {
        let mut v = mint.to_bytes().to_vec();
        v.extend_from_slice(&group.to_bytes());
        v.extend_from_slice(&7u64.to_le_bytes());
        v
    }

    #[test]
    fn finds_the_group_after_other_extensions() {
        let mint = Pubkey::new_unique();
        let group = Pubkey::new_unique();
        let data = mint_with(&[(18, vec![1u8; 64]), (EXTENSION_TOKEN_GROUP_MEMBER, member(&mint, &group))]);
        assert_eq!(member_group(&mint, &data), Some(group));
    }

    #[test]
    fn rejects_a_member_record_for_another_mint() {
        let mint = Pubkey::new_unique();
        let data = mint_with(&[(EXTENSION_TOKEN_GROUP_MEMBER, member(&Pubkey::new_unique(), &Pubkey::new_unique()))]);
        assert_eq!(member_group(&mint, &data), None);
    }

    /// A real Seeker Genesis Token mint, fetched from mainnet by
    /// spikes/switchboard-devnet/find-sgt.mjs.
    #[test]
    fn recognises_a_real_sgt_from_mainnet() {
        let fixture = include_str!("../tests/fixtures/sgt-member-mint.json");
        let field = |name: &str| {
            let start = fixture.find(&format!("\"{name}\": \"")).unwrap() + name.len() + 5;
            &fixture[start..start + fixture[start..].find('"').unwrap()]
        };
        let mint: Pubkey = field("mint").parse().unwrap();
        let data = base64_decode(field("data"));
        let sgt_group: Pubkey = "GT22s89nU4iWFkNXj1Bw6uYhJJWDRPpShHt4Bk8f99Te".parse().unwrap();
        assert_eq!(member_group(&mint, &data), Some(sgt_group));
        assert_eq!(member_group(&Pubkey::new_unique(), &data), None);
    }

    fn base64_decode(s: &str) -> Vec<u8> {
        let table = |c: u8| match c {
            b'A'..=b'Z' => c - b'A',
            b'a'..=b'z' => c - b'a' + 26,
            b'0'..=b'9' => c - b'0' + 52,
            b'+' => 62,
            _ => 63,
        };
        let bytes: Vec<u8> = s.bytes().filter(|&c| c != b'=').map(table).collect();
        let mut out = Vec::new();
        for chunk in bytes.chunks(4) {
            let n = chunk.iter().enumerate().fold(0u32, |acc, (i, &v)| acc | (v as u32) << (18 - 6 * i));
            out.extend_from_slice(&n.to_be_bytes()[1..chunk.len()]);
        }
        out
    }

    #[test]
    fn rejects_plain_mints_and_truncated_data() {
        let mint = Pubkey::new_unique();
        assert_eq!(member_group(&mint, &[0u8; 82]), None);
        let mut data = mint_with(&[(EXTENSION_TOKEN_GROUP_MEMBER, member(&mint, &Pubkey::new_unique()))]);
        data.truncate(data.len() - 1);
        assert_eq!(member_group(&mint, &data), None);
    }
}
