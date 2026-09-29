//! A free nightly draw for Seeker owners. Pick 5 of 85 before entries close;
//! at draw time Switchboard randomness picks the winning numbers and the
//! tickets with the most matches split a pot that sponsors fund in SKR.
//! Nobody ever pays to enter: a payment would make this a lottery.

use anchor_lang::prelude::*;
use anchor_spl::token_interface::{self, Mint, TokenAccount, TokenInterface, TransferChecked};
use switchboard_on_demand::accounts::RandomnessAccountData;

pub mod logic;
pub mod sgt;

use logic::*;

declare_id!("gvd3fv3QgWvTMzLfxN2HBKkspAeVwzGBCZkW9ixaucM");

pub const UNSCORED: u8 = u8::MAX;
/// A committed draw that nobody revealed for this many slots (~2 minutes) may be
/// committed again with fresh randomness, so an oracle outage cannot strand a
/// pot. Anyone can reveal before then, so withholding a bad result gains nothing
/// while any honest client is running.
pub const REVEAL_TIMEOUT_SLOTS: u64 = 300;
pub const TOKEN_2022_ID: Pubkey = pubkey!("TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb");

#[program]
pub mod daily_draw {
    use super::*;

    /// Only the program's upgrade authority may initialize: the config is fixed
    /// forever, so whoever sets it first (Switchboard program, queue, mint)
    /// decides every draw.
    pub fn initialize(ctx: Context<Initialize>, params: InitParams) -> Result<()> {
        require!(params.round_secs > 0, DrawError::BadConfig);
        require!(params.entry_secs > 0 && params.entry_secs < params.round_secs, DrawError::BadConfig);
        let config = &mut ctx.accounts.config;
        config.admin = ctx.accounts.admin.key();
        config.mint = ctx.accounts.mint.key();
        config.genesis_ts = params.genesis_ts;
        config.round_secs = params.round_secs;
        config.entry_secs = params.entry_secs;
        config.per_ticket_bonus = params.per_ticket_bonus;
        config.require_sgt = params.require_sgt;
        config.sgt_group = params.sgt_group;
        config.sb_program = params.sb_program;
        config.sb_queue = params.sb_queue;
        config.sponsor_budget = 0;
        config.carry = 0;
        config.bump = ctx.bumps.config;
        Ok(())
    }

    /// A sponsor adds SKR. Each ticket then moves `per_ticket_bonus` of it into
    /// that night's pot, so the pot grows in front of everyone as people enter.
    pub fn fund(ctx: Context<Fund>, amount: u64) -> Result<()> {
        require!(amount > 0, DrawError::ZeroAmount);
        let before = ctx.accounts.vault.amount;
        let accounts = TransferChecked {
            from: ctx.accounts.sponsor_tokens.to_account_info(),
            mint: ctx.accounts.mint.to_account_info(),
            to: ctx.accounts.vault.to_account_info(),
            authority: ctx.accounts.sponsor.to_account_info(),
        };
        token_interface::transfer_checked(
            CpiContext::new(ctx.accounts.token_program.to_account_info(), accounts),
            amount,
            ctx.accounts.mint.decimals,
        )?;
        // Credit what arrived, not what was sent: a Token-2022 transfer fee would
        // otherwise let the budget promise tokens the vault does not hold.
        ctx.accounts.vault.reload()?;
        let received = ctx.accounts.vault.amount.checked_sub(before).ok_or(DrawError::Overflow)?;
        let config = &mut ctx.accounts.config;
        config.sponsor_budget = config.sponsor_budget.checked_add(received).ok_or(DrawError::Overflow)?;
        emit!(Funded { sponsor: ctx.accounts.sponsor.key(), amount: received });
        Ok(())
    }

    pub fn enter(ctx: Context<Enter>, round_id: u64, index: u8, picks: [u8; PICKS]) -> Result<()> {
        let now = Clock::get()?.unix_timestamp;
        let config = &mut ctx.accounts.config;
        require!(config.round_at(now) == Some(round_id), DrawError::NotCurrentRound);
        let (close_ts, draw_ts) = config.round_times(round_id);
        require!(now < close_ts, DrawError::EntriesClosed);
        let picks = normalize_picks(picks).ok_or(DrawError::InvalidPicks)?;

        let player = ctx.accounts.player.key();
        let identity = ctx.accounts.identity.key();
        if config.require_sgt {
            let sgt_tokens = ctx.accounts.sgt_tokens.as_ref().ok_or(DrawError::NotASeeker)?;
            require_keys_eq!(*ctx.accounts.identity.owner, TOKEN_2022_ID, DrawError::NotASeeker);
            require_keys_eq!(sgt_tokens.mint, identity, DrawError::NotASeeker);
            require_keys_eq!(sgt_tokens.owner, player, DrawError::NotASeeker);
            require!(sgt_tokens.amount >= 1, DrawError::NotASeeker);
            let data = ctx.accounts.identity.try_borrow_data()?;
            require!(
                sgt::member_group(&identity, &data) == Some(config.sgt_group),
                DrawError::NotASeeker
            );
        } else {
            // Demo mode (devnet, no Seeker): the wallet itself is the identity.
            require_keys_eq!(identity, player, DrawError::NotASeeker);
        }

        let round = &mut ctx.accounts.round;
        if round.draw_ts == 0 {
            round.id = round_id;
            round.close_ts = close_ts;
            round.draw_ts = draw_ts;
            round.pot = std::mem::take(&mut config.carry);
            round.best = 0;
            round.status = RoundStatus::Open;
            round.creator = player;
            round.bump = ctx.bumps.round;
        }
        require!(round.status == RoundStatus::Open, DrawError::EntriesClosed);

        let seeker = &mut ctx.accounts.seeker;
        let last = seeker.entered.then_some(seeker.last_round);
        if last != Some(round_id) {
            seeker.tickets_in_round = 0;
        }
        let streak = next_streak(last, seeker.streak, round_id);
        require!(index == seeker.tickets_in_round, DrawError::WrongTicketIndex);
        require!(seeker.tickets_in_round < tickets_allowed(streak), DrawError::TicketLimit);
        seeker.identity = identity;
        seeker.owner = player;
        seeker.entered = true;
        seeker.last_round = round_id;
        seeker.streak = streak;
        seeker.tickets_in_round += 1;
        seeker.bump = ctx.bumps.seeker;

        let ticket = &mut ctx.accounts.ticket;
        ticket.round = round_id;
        ticket.owner = player;
        ticket.identity = identity;
        ticket.picks = picks;
        ticket.matches = UNSCORED;
        ticket.claimed = false;
        ticket.bump = ctx.bumps.ticket;

        round.tickets = round.tickets.checked_add(1).ok_or(DrawError::Overflow)?;
        round.open_tickets = round.open_tickets.checked_add(1).ok_or(DrawError::Overflow)?;
        let bonus = config.per_ticket_bonus.min(config.sponsor_budget);
        config.sponsor_budget -= bonus;
        round.pot = round.pot.checked_add(bonus).ok_or(DrawError::Overflow)?;

        emit!(Entered { round: round_id, owner: player, identity, index, picks, pot: round.pot });
        Ok(())
    }

    /// Binds the round to a Switchboard randomness account committed in this
    /// same transaction, after entries closed: nobody could know the result
    /// while they could still enter. Anyone may call it. The seed slot is kept so
    /// the reveal must be of this very commitment (see reveal_draw).
    pub fn commit_draw(ctx: Context<CommitDraw>, round_id: u64) -> Result<()> {
        let clock = Clock::get()?;
        let round = &mut ctx.accounts.round;
        match round.status {
            RoundStatus::Open => {}
            RoundStatus::Committed => require!(
                clock.slot > round.commit_slot.saturating_add(REVEAL_TIMEOUT_SLOTS),
                DrawError::RevealPending
            ),
            _ => return err!(DrawError::WrongStatus),
        }
        require!(clock.unix_timestamp >= round.draw_ts, DrawError::TooEarly);
        let data = RandomnessAccountData::parse(ctx.accounts.randomness.try_borrow_data()?)
            .map_err(|_| DrawError::BadRandomness)?;
        require_keys_eq!(data.queue, ctx.accounts.config.sb_queue, DrawError::BadRandomness);
        require!(data.seed_slot == clock.slot.saturating_sub(1), DrawError::StaleCommit);
        round.randomness = ctx.accounts.randomness.key();
        round.commit_slot = data.seed_slot;
        round.status = RoundStatus::Committed;
        emit!(DrawCommitted { round: round_id, randomness: round.randomness });
        Ok(())
    }

    /// Reads the revealed value in the same transaction as Switchboard's reveal
    /// and turns it into the winning numbers. Anyone may call it. The value must
    /// come from the commitment made at commit_draw: without the seed-slot check,
    /// whoever cranks could reveal privately, re-commit the same account, and
    /// repeat until the numbers favour their own ticket.
    pub fn reveal_draw(ctx: Context<RevealDraw>, round_id: u64) -> Result<()> {
        let clock = Clock::get()?;
        let round = &mut ctx.accounts.round;
        require!(round.status == RoundStatus::Committed, DrawError::WrongStatus);
        require_keys_eq!(ctx.accounts.randomness.key(), round.randomness, DrawError::BadRandomness);
        let data = RandomnessAccountData::parse(ctx.accounts.randomness.try_borrow_data()?)
            .map_err(|_| DrawError::BadRandomness)?;
        require!(data.seed_slot == round.commit_slot, DrawError::RandomnessExpired);
        let value = data.get_value(clock.slot).map_err(|_| DrawError::NotRevealed)?;
        round.winning = draw_five(&value);
        round.status = RoundStatus::Revealed;
        emit!(DrawRevealed { round: round_id, winning: round.winning, tickets: round.tickets, pot: round.pot });
        Ok(())
    }

    /// Scores any number of the round's tickets passed as remaining accounts.
    /// When the last one is scored the round settles: the best match splits the
    /// pot, and a night where nobody matched anything rolls it to the next one.
    pub fn score_tickets<'info>(ctx: Context<'_, '_, 'info, 'info, ScoreTickets<'info>>, round_id: u64) -> Result<()> {
        let round = &mut ctx.accounts.round;
        require!(round.status == RoundStatus::Revealed, DrawError::WrongStatus);
        for info in ctx.remaining_accounts.iter() {
            require!(info.is_writable, DrawError::BadTicket);
            let mut ticket: Account<Ticket> = Account::try_from(info)?;
            require!(ticket.round == round_id, DrawError::BadTicket);
            if ticket.matches != UNSCORED {
                continue;
            }
            let matches = count_matches(&ticket.picks, &round.winning);
            ticket.matches = matches;
            ticket.exit(&crate::ID)?;
            if matches > round.best {
                round.best = matches;
                round.winners = 1;
            } else if matches == round.best && matches > 0 {
                round.winners += 1;
            }
            round.scored += 1;
        }
        if round.scored == round.tickets {
            let config = &mut ctx.accounts.config;
            if round.best == 0 {
                config.carry = config.carry.checked_add(round.pot).ok_or(DrawError::Overflow)?;
                round.share = 0;
            } else {
                round.share = round.pot / round.winners as u64;
                let dust = round.pot - round.share * round.winners as u64;
                config.carry = config.carry.checked_add(dust).ok_or(DrawError::Overflow)?;
            }
            round.status = RoundStatus::Settled;
            emit!(RoundSettled { round: round_id, best: round.best, winners: round.winners, share: round.share });
        }
        Ok(())
    }

    /// Pays a winning ticket and closes it, returning its rent to the owner.
    pub fn claim(ctx: Context<Claim>, round_id: u64) -> Result<()> {
        let round = &mut ctx.accounts.round;
        let ticket = &mut ctx.accounts.ticket;
        require!(round.status == RoundStatus::Settled, DrawError::WrongStatus);
        require!(round.best > 0 && ticket.matches == round.best, DrawError::NotAWinner);
        require!(!ticket.claimed, DrawError::AlreadyClaimed);
        ticket.claimed = true;
        round.open_tickets = round.open_tickets.saturating_sub(1);
        let share = round.share;

        let seeds: &[&[u8]] = &[b"config", &[ctx.accounts.config.bump]];
        let accounts = TransferChecked {
            from: ctx.accounts.vault.to_account_info(),
            mint: ctx.accounts.mint.to_account_info(),
            to: ctx.accounts.owner_tokens.to_account_info(),
            authority: ctx.accounts.config.to_account_info(),
        };
        token_interface::transfer_checked(
            CpiContext::new_with_signer(ctx.accounts.token_program.to_account_info(), accounts, &[seeds]),
            share,
            ctx.accounts.mint.decimals,
        )?;
        emit!(Claimed { round: round_id, owner: ticket.owner, amount: share });
        Ok(())
    }

    /// Closes a settled ticket that has nothing left to claim and returns its
    /// rent to the owner, so entering costs only the transaction fee.
    pub fn close_ticket(ctx: Context<CloseTicket>, _round_id: u64) -> Result<()> {
        let round = &mut ctx.accounts.round;
        let ticket = &ctx.accounts.ticket;
        require!(round.status == RoundStatus::Settled, DrawError::WrongStatus);
        let unpaid_win = round.best > 0 && ticket.matches == round.best && !ticket.claimed;
        require!(!unpaid_win, DrawError::UnclaimedPrize);
        round.open_tickets = round.open_tickets.saturating_sub(1);
        Ok(())
    }

    /// The admin retimes future rounds: the demo's two-minute rounds closed while
    /// a player was still approving in the wallet. Numbering may only move
    /// forward (logic::schedule_change_ok), and rounds that already exist keep
    /// their stored close and draw times.
    pub fn set_schedule(ctx: Context<SetSchedule>, genesis_ts: i64, round_secs: i64, entry_secs: i64) -> Result<()> {
        let now = Clock::get()?.unix_timestamp;
        let config = &mut ctx.accounts.config;
        require!(
            schedule_change_ok(config.genesis_ts, config.round_secs, genesis_ts, round_secs, entry_secs, now),
            DrawError::BadConfig
        );
        config.genesis_ts = genesis_ts;
        config.round_secs = round_secs;
        config.entry_secs = entry_secs;
        emit!(ScheduleChanged { genesis_ts, round_secs, entry_secs });
        Ok(())
    }

    /// Once every ticket of a settled round is closed, anyone may close the
    /// round; its rent goes back to whoever created it with the first ticket.
    pub fn close_round(ctx: Context<CloseRound>, _round_id: u64) -> Result<()> {
        let round = &ctx.accounts.round;
        require!(round.status == RoundStatus::Settled, DrawError::WrongStatus);
        require!(round.open_tickets == 0, DrawError::TicketsOpen);
        Ok(())
    }
}

#[derive(AnchorSerialize, AnchorDeserialize, Clone)]
pub struct InitParams {
    pub genesis_ts: i64,
    pub round_secs: i64,
    pub entry_secs: i64,
    pub per_ticket_bonus: u64,
    pub require_sgt: bool,
    pub sgt_group: Pubkey,
    pub sb_program: Pubkey,
    pub sb_queue: Pubkey,
}

#[account]
#[derive(InitSpace)]
pub struct Config {
    pub admin: Pubkey,
    pub mint: Pubkey,
    pub genesis_ts: i64,
    pub round_secs: i64,
    pub entry_secs: i64,
    pub per_ticket_bonus: u64,
    pub sponsor_budget: u64,
    /// Pot waiting for the next round: nights nobody matched, and division dust.
    pub carry: u64,
    pub require_sgt: bool,
    pub sgt_group: Pubkey,
    pub sb_program: Pubkey,
    pub sb_queue: Pubkey,
    pub bump: u8,
}

impl Config {
    pub fn round_at(&self, now: i64) -> Option<u64> {
        (now >= self.genesis_ts).then(|| ((now - self.genesis_ts) / self.round_secs) as u64)
    }

    /// (entries close, draw) for a round.
    pub fn round_times(&self, round_id: u64) -> (i64, i64) {
        let start = self.genesis_ts + round_id as i64 * self.round_secs;
        (start + self.entry_secs, start + self.round_secs)
    }
}

#[derive(AnchorSerialize, AnchorDeserialize, Clone, Copy, PartialEq, Eq, InitSpace, Default)]
pub enum RoundStatus {
    #[default]
    Open,
    Committed,
    Revealed,
    Settled,
}

#[account]
#[derive(InitSpace)]
pub struct Round {
    pub id: u64,
    pub close_ts: i64,
    pub draw_ts: i64,
    pub pot: u64,
    pub tickets: u32,
    pub scored: u32,
    pub best: u8,
    pub winners: u32,
    pub share: u64,
    pub randomness: Pubkey,
    /// Seed slot of the committed randomness; the reveal must match it.
    pub commit_slot: u64,
    pub winning: [u8; PICKS],
    pub status: RoundStatus,
    /// Paid the round's rent with the first ticket; gets it back at close_round.
    pub creator: Pubkey,
    /// Tickets not yet claimed or closed.
    pub open_tickets: u32,
    pub bump: u8,
}

/// One per Seeker (per SGT; per wallet in demo mode): the streak that earns tickets.
#[account]
#[derive(InitSpace)]
pub struct Seeker {
    pub identity: Pubkey,
    pub owner: Pubkey,
    pub entered: bool,
    pub last_round: u64,
    pub streak: u32,
    pub tickets_in_round: u8,
    pub bump: u8,
}

#[account]
#[derive(InitSpace)]
pub struct Ticket {
    pub round: u64,
    pub owner: Pubkey,
    pub identity: Pubkey,
    pub picks: [u8; PICKS],
    pub matches: u8,
    pub claimed: bool,
    pub bump: u8,
}

#[derive(Accounts)]
pub struct Initialize<'info> {
    #[account(mut)]
    pub admin: Signer<'info>,
    #[account(constraint = program.programdata_address()? == Some(program_data.key()) @ DrawError::NotUpgradeAuthority)]
    pub program: Program<'info, crate::program::DailyDraw>,
    #[account(constraint = program_data.upgrade_authority_address == Some(admin.key()) @ DrawError::NotUpgradeAuthority)]
    pub program_data: Account<'info, ProgramData>,
    #[account(init, payer = admin, space = 8 + Config::INIT_SPACE, seeds = [b"config"], bump)]
    pub config: Account<'info, Config>,
    pub mint: InterfaceAccount<'info, Mint>,
    #[account(
        init, payer = admin, seeds = [b"vault"], bump,
        token::mint = mint, token::authority = config, token::token_program = token_program
    )]
    pub vault: InterfaceAccount<'info, TokenAccount>,
    pub token_program: Interface<'info, TokenInterface>,
    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
pub struct SetSchedule<'info> {
    pub admin: Signer<'info>,
    #[account(mut, seeds = [b"config"], bump = config.bump, has_one = admin @ DrawError::NotAdmin)]
    pub config: Account<'info, Config>,
}

#[derive(Accounts)]
pub struct Fund<'info> {
    pub sponsor: Signer<'info>,
    #[account(mut, seeds = [b"config"], bump = config.bump, has_one = mint)]
    pub config: Account<'info, Config>,
    pub mint: InterfaceAccount<'info, Mint>,
    #[account(mut, token::mint = mint, token::authority = sponsor, token::token_program = token_program)]
    pub sponsor_tokens: InterfaceAccount<'info, TokenAccount>,
    #[account(mut, seeds = [b"vault"], bump)]
    pub vault: InterfaceAccount<'info, TokenAccount>,
    pub token_program: Interface<'info, TokenInterface>,
}

#[derive(Accounts)]
#[instruction(round_id: u64, index: u8)]
pub struct Enter<'info> {
    #[account(mut)]
    pub player: Signer<'info>,
    #[account(mut, seeds = [b"config"], bump = config.bump)]
    pub config: Account<'info, Config>,
    /// CHECK: the SGT mint (checked in the handler), or the player's own key in demo mode.
    pub identity: UncheckedAccount<'info>,
    /// The player's token account holding the SGT; required when the config demands one.
    pub sgt_tokens: Option<InterfaceAccount<'info, TokenAccount>>,
    #[account(
        init_if_needed, payer = player, space = 8 + Round::INIT_SPACE,
        seeds = [b"round", round_id.to_le_bytes().as_ref()], bump
    )]
    pub round: Account<'info, Round>,
    #[account(
        init_if_needed, payer = player, space = 8 + Seeker::INIT_SPACE,
        seeds = [b"seeker", identity.key().as_ref()], bump
    )]
    pub seeker: Account<'info, Seeker>,
    #[account(
        init, payer = player, space = 8 + Ticket::INIT_SPACE,
        seeds = [b"ticket", round_id.to_le_bytes().as_ref(), identity.key().as_ref(), &[index]], bump
    )]
    pub ticket: Account<'info, Ticket>,
    pub system_program: Program<'info, System>,
}

#[derive(Accounts)]
#[instruction(round_id: u64)]
pub struct CommitDraw<'info> {
    #[account(seeds = [b"config"], bump = config.bump)]
    pub config: Account<'info, Config>,
    #[account(mut, seeds = [b"round", round_id.to_le_bytes().as_ref()], bump = round.bump)]
    pub round: Account<'info, Round>,
    /// CHECK: owner pinned to the configured Switchboard program; contents parsed in the handler.
    #[account(owner = config.sb_program)]
    pub randomness: UncheckedAccount<'info>,
}

#[derive(Accounts)]
#[instruction(round_id: u64)]
pub struct RevealDraw<'info> {
    #[account(seeds = [b"config"], bump = config.bump)]
    pub config: Account<'info, Config>,
    #[account(mut, seeds = [b"round", round_id.to_le_bytes().as_ref()], bump = round.bump)]
    pub round: Account<'info, Round>,
    /// CHECK: must be the account the round committed to, owned by Switchboard.
    #[account(owner = config.sb_program)]
    pub randomness: UncheckedAccount<'info>,
}

#[derive(Accounts)]
#[instruction(round_id: u64)]
pub struct ScoreTickets<'info> {
    #[account(mut, seeds = [b"config"], bump = config.bump)]
    pub config: Account<'info, Config>,
    #[account(mut, seeds = [b"round", round_id.to_le_bytes().as_ref()], bump = round.bump)]
    pub round: Account<'info, Round>,
}

#[derive(Accounts)]
#[instruction(round_id: u64)]
pub struct Claim<'info> {
    /// CHECK: the ticket's owner (has_one below); receives the ticket's rent and,
    /// through owner_tokens, the prize. Not a signer: paying a winner is
    /// permissionless, so the crank pays everyone right after the draw and the
    /// player never has to come back and sign. Nothing here can route the prize
    /// or the rent to whoever sends the transaction.
    #[account(mut)]
    pub owner: UncheckedAccount<'info>,
    #[account(seeds = [b"config"], bump = config.bump, has_one = mint)]
    pub config: Account<'info, Config>,
    #[account(mut, seeds = [b"round", round_id.to_le_bytes().as_ref()], bump = round.bump)]
    pub round: Account<'info, Round>,
    #[account(mut, has_one = owner, close = owner, constraint = ticket.round == round_id @ DrawError::BadTicket)]
    pub ticket: Account<'info, Ticket>,
    pub mint: InterfaceAccount<'info, Mint>,
    #[account(mut, seeds = [b"vault"], bump)]
    pub vault: InterfaceAccount<'info, TokenAccount>,
    #[account(mut, token::mint = mint, token::authority = owner, token::token_program = token_program)]
    pub owner_tokens: InterfaceAccount<'info, TokenAccount>,
    pub token_program: Interface<'info, TokenInterface>,
}

#[derive(Accounts)]
#[instruction(round_id: u64)]
pub struct CloseTicket<'info> {
    /// CHECK: the ticket's owner (has_one below), who gets the rent back. Closing a
    /// settled ticket with nothing to claim is permissionless for the same reason
    /// as claim, and it can only ever return the rent to its owner.
    #[account(mut)]
    pub owner: UncheckedAccount<'info>,
    #[account(mut, seeds = [b"round", round_id.to_le_bytes().as_ref()], bump = round.bump)]
    pub round: Account<'info, Round>,
    #[account(mut, has_one = owner, close = owner, constraint = ticket.round == round_id @ DrawError::BadTicket)]
    pub ticket: Account<'info, Ticket>,
}

#[derive(Accounts)]
#[instruction(round_id: u64)]
pub struct CloseRound<'info> {
    /// CHECK: receives the round's rent; must be the round's creator.
    #[account(mut, address = round.creator @ DrawError::NotCreator)]
    pub creator: UncheckedAccount<'info>,
    #[account(mut, close = creator, seeds = [b"round", round_id.to_le_bytes().as_ref()], bump = round.bump)]
    pub round: Account<'info, Round>,
}

#[event]
pub struct ScheduleChanged {
    pub genesis_ts: i64,
    pub round_secs: i64,
    pub entry_secs: i64,
}

#[event]
pub struct Funded {
    pub sponsor: Pubkey,
    pub amount: u64,
}

#[event]
pub struct Entered {
    pub round: u64,
    pub owner: Pubkey,
    pub identity: Pubkey,
    pub index: u8,
    pub picks: [u8; PICKS],
    pub pot: u64,
}

#[event]
pub struct DrawCommitted {
    pub round: u64,
    pub randomness: Pubkey,
}

#[event]
pub struct DrawRevealed {
    pub round: u64,
    pub winning: [u8; PICKS],
    pub tickets: u32,
    pub pot: u64,
}

#[event]
pub struct RoundSettled {
    pub round: u64,
    pub best: u8,
    pub winners: u32,
    pub share: u64,
}

#[event]
pub struct Claimed {
    pub round: u64,
    pub owner: Pubkey,
    pub amount: u64,
}

#[error_code]
pub enum DrawError {
    #[msg("Round length and entry window must be positive, with entries closing before the draw")]
    BadConfig,
    #[msg("Amount must be greater than zero")]
    ZeroAmount,
    #[msg("Arithmetic overflow")]
    Overflow,
    #[msg("Tickets can only be entered for the current round")]
    NotCurrentRound,
    #[msg("Entries for this round are closed")]
    EntriesClosed,
    #[msg("Pick 5 different numbers from 1 to 85")]
    InvalidPicks,
    #[msg("A Seeker Genesis Token held by this wallet is required")]
    NotASeeker,
    #[msg("Ticket index must be the next free one for this Seeker")]
    WrongTicketIndex,
    #[msg("No tickets left for this Seeker in this round")]
    TicketLimit,
    #[msg("The round is not in the right state for this")]
    WrongStatus,
    #[msg("The draw time has not come yet")]
    TooEarly,
    #[msg("Randomness account is not the expected Switchboard account")]
    BadRandomness,
    #[msg("Randomness must be committed in the same transaction")]
    StaleCommit,
    #[msg("Randomness is not revealed in this slot")]
    NotRevealed,
    #[msg("Ticket does not belong to this round")]
    BadTicket,
    #[msg("This ticket did not win")]
    NotAWinner,
    #[msg("Already claimed")]
    AlreadyClaimed,
    #[msg("Randomness was re-committed after this round's commit")]
    RandomnessExpired,
    #[msg("The committed draw can still be revealed")]
    RevealPending,
    #[msg("Claim the prize first; claiming also returns the rent")]
    UnclaimedPrize,
    #[msg("Some tickets of this round are still open")]
    TicketsOpen,
    #[msg("Rent goes back to the round's creator")]
    NotCreator,
    #[msg("Only the program's upgrade authority can initialize")]
    NotUpgradeAuthority,
    #[msg("Only the admin can change the schedule")]
    NotAdmin,
}
