//! 每个插件实例一个定时线程：到点只投递 id，回调仍在插件线程执行。
//! One timer thread per plugin instance; it only posts ids, callbacks run on the plugin thread.

use std::collections::HashMap;
use std::sync::mpsc::{self, RecvTimeoutError, Sender};
use std::time::{Duration, Instant};

enum Cmd {
    Add {
        id: u64,
        delay: Duration,
        interval: Option<Duration>,
    },
    Cancel(u64),
}

pub struct Timers {
    tx: Sender<Cmd>,
}

impl Timers {
    /// `fire(id)` 在定时线程上调用，应只做投递。`fire` runs on the timer thread; keep it cheap.
    pub fn spawn(fire: impl Fn(u64) + Send + 'static) -> Timers {
        let (tx, rx) = mpsc::channel::<Cmd>();
        std::thread::Builder::new()
            .name("weave-timer".into())
            .spawn(move || {
                let mut entries: HashMap<u64, (Instant, Option<Duration>)> = HashMap::new();
                loop {
                    let now = Instant::now();
                    let due: Vec<u64> = entries
                        .iter()
                        .filter(|(_, (at, _))| *at <= now)
                        .map(|(id, _)| *id)
                        .collect();
                    for id in due {
                        fire(id);
                        match entries.get(&id).and_then(|e| e.1) {
                            Some(iv) => {
                                entries.insert(id, (now + iv, Some(iv)));
                            }
                            None => {
                                entries.remove(&id);
                            }
                        }
                    }
                    let next = entries.values().map(|(at, _)| *at).min();
                    let cmd = match next {
                        Some(at) => rx.recv_timeout(at.saturating_duration_since(Instant::now())),
                        None => rx.recv().map_err(|_| RecvTimeoutError::Disconnected),
                    };
                    match cmd {
                        Ok(Cmd::Add {
                            id,
                            delay,
                            interval,
                        }) => {
                            entries.insert(id, (Instant::now() + delay, interval));
                        }
                        Ok(Cmd::Cancel(id)) => {
                            entries.remove(&id);
                        }
                        Err(RecvTimeoutError::Timeout) => {}
                        Err(RecvTimeoutError::Disconnected) => return,
                    }
                }
            })
            .expect("spawn timer thread");
        Timers { tx }
    }

    pub fn add(&self, id: u64, delay: Duration, interval: Option<Duration>) {
        let _ = self.tx.send(Cmd::Add {
            id,
            delay,
            interval,
        });
    }

    pub fn cancel(&self, id: u64) {
        let _ = self.tx.send(Cmd::Cancel(id));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn once_and_interval() {
        let (tx, rx) = mpsc::channel();
        let t = Timers::spawn(move |id| {
            let _ = tx.send(id);
        });
        t.add(1, Duration::from_millis(10), None);
        t.add(2, Duration::from_millis(5), Some(Duration::from_millis(5)));
        let mut got = Vec::new();
        for _ in 0..6 {
            got.push(rx.recv_timeout(Duration::from_secs(2)).unwrap());
        }
        t.cancel(2);
        assert_eq!(got.iter().filter(|&&i| i == 1).count(), 1);
        assert!(got.iter().filter(|&&i| i == 2).count() >= 4);
        std::thread::sleep(Duration::from_millis(30));
        while rx.try_recv().is_ok() {}
        assert!(rx.recv_timeout(Duration::from_millis(50)).is_err());
    }
}
