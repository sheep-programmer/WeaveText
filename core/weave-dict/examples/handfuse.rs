//! 卷积网络与模板匹配融合的调参（仅开发用）。 Tuning the CNN + template fusion (development only).
//! 用法 / Usage: `handfuse <hand.wvn> <hand.wvh> <samples.txt> <limit>`
use weave_dict::hand::{Recognizer, Stroke};
use weave_dict::handnet::HandNet;

fn main() {
    let a: Vec<String> = std::env::args().skip(1).collect();
    let net = HandNet::from_bytes(&std::fs::read(&a[0]).unwrap()).unwrap();
    let rec = Recognizer::from_bytes(&std::fs::read(&a[1]).unwrap()).unwrap();
    let limit: usize = a[3].parse().unwrap();
    let text = std::fs::read_to_string(&a[2]).unwrap();
    let mut rows = Vec::new();
    for line in text.lines().take(limit) {
        let Some((c, ink)) = line.split_once('\t') else { continue };
        let c = c.chars().next().unwrap();
        let s: Vec<Stroke> = ink.split(';').map(|s| s.split(' ').filter_map(|p| p.split_once(','))
            .filter_map(|(x, y)| Some((x.parse().ok()?, y.parse().ok()?))).collect()).filter(|s: &Stroke| !s.is_empty()).collect();
        rows.push((c, net.recognize(&s, 30), rec.recognize(&s, 30)));
    }
    for alpha in [0.0f32, 1.0, 2.0, 3.0] {
        for beta in [0.0f32, 2.0, 4.0, 8.0, 12.0, 16.0] {
            let (mut t1, mut t5) = (0, 0);
            for (c, n, t) in &rows {
                let worst = t.last().map_or(1.0, |x| x.1) + 0.05;
                let mut cand: Vec<char> = n.iter().map(|x| x.0).chain(t.iter().map(|x| x.0)).collect();
                cand.sort(); cand.dedup();
                let pmin = n.last().map_or(1e-6, |x| x.1).max(1e-6) * 0.5;
                let mut sc: Vec<(char, f32)> = cand.iter().map(|&k| {
                    let p = n.iter().find(|x| x.0 == k).map_or(pmin, |x| x.1);
                    let cost = t.iter().find(|x| x.0 == k).map_or(worst, |x| x.1);
                    let prior = rec.prior_of(k);
                    (k, p.ln() - beta * cost + alpha * prior)
                }).collect();
                sc.sort_by(|x, y| y.1.partial_cmp(&x.1).unwrap());
                let r = sc.iter().position(|x| x.0 == *c);
                t1 += (r == Some(0)) as u32; t5 += r.is_some_and(|r| r < 5) as u32;
            }
            println!("alpha {alpha} beta {beta}: top1 {:.1}% top5 {:.1}%", t1 as f32 * 100.0 / rows.len() as f32, t5 as f32 * 100.0 / rows.len() as f32);
        }
    }
}
