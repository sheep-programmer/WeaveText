use weave_engine::special::{eval, is_expression, money_upper, v_accepts, v_candidates};

#[test]
fn unicode_operators_share_the_expression_and_input_gates() {
    for (expression, result) in [
        ("（12＋3）×2÷5", 6.0),
        ("8－3", 5.0),
        ("8−3", 5.0),
        ("1．5＋2．5", 4.0),
        ("\t1\n＋ 2", 3.0),
    ] {
        assert_eq!(eval(expression), Some(result), "{expression}");
        assert!(is_expression(expression), "{expression}");
        assert_eq!(v_candidates(expression)[0].0, result.to_string());
    }
    for c in "0123456789.+-*/()%^×÷＋－−（）．=".chars() {
        assert!(v_accepts(c), "{c}");
    }
    assert!(!is_expression("−1234．50"));
}

#[test]
fn a_single_terminal_equals_does_not_duplicate_the_equation_equals() {
    assert_eq!(
        v_candidates("（12＋3）×2="),
        vec![
            ("30".into(), "计算结果".into()),
            ("（12＋3）×2=30".into(), "算式".into()),
            ("叁拾元整".into(), "大写金额".into()),
        ]
    );
    assert_eq!(v_candidates("1234="), v_candidates("1234"));
    for body in ["1+2==", "1=2", "1=+2", "=1234", "1234==", "="] {
        assert!(v_candidates(body).is_empty(), "{body}");
    }
}

#[test]
fn fullwidth_equals_terminates_once_and_preserves_the_typed_equation() {
    assert!(v_accepts('＝'));
    assert_eq!(
        v_candidates("（12＋3）×2＝"),
        vec![
            ("30".into(), "计算结果".into()),
            ("（12＋3）×2=30".into(), "算式".into()),
            ("叁拾元整".into(), "大写金额".into()),
        ]
    );
    assert_eq!(v_candidates("1234＝"), v_candidates("1234"));
    for body in ["1＋2＝＝", "1＋2=＝", "1＋2＝=", "1＝2", "1＝＋2", "＝1234", "＝"] {
        assert!(v_candidates(body).is_empty(), "{body}");
    }
}

#[test]
fn signed_decimals_keep_precision_and_candidate_order() {
    assert_eq!(
        v_candidates("-1234.50"),
        vec![
            ("-1234.50".into(), "".into()),
            ("负壹仟贰佰叁拾肆元伍角".into(), "大写金额".into()),
            ("负一千二百三十四点五零".into(), "中文数字".into()),
            ("-1,234.50".into(), "千分位".into()),
        ]
    );
    assert_eq!(money_upper("-1234.50").as_deref(), Some("负壹仟贰佰叁拾肆元伍角"));
}

#[test]
fn positive_decimals_get_chinese_reading_and_grouping() {
    assert_eq!(
        v_candidates("1234.50"),
        vec![
            ("1234.50".into(), "".into()),
            ("壹仟贰佰叁拾肆元伍角".into(), "大写金额".into()),
            ("一千二百三十四点五零".into(), "中文数字".into()),
            ("1,234.50".into(), "千分位".into()),
        ]
    );
    let unicode = v_candidates("−1234．50");
    assert_eq!(unicode[1].0, "负壹仟贰佰叁拾肆元伍角");
    assert_eq!(unicode[2].0, "负一千二百三十四点五零");
    assert_eq!(unicode[3].0, "-1,234.50");
    assert_eq!(v_candidates("+1234.50")[2].0, "一千二百三十四点五零");
    assert_eq!(v_candidates("12.05")[2].0, "十二点零五");
}

#[test]
fn financial_signs_do_not_create_negative_zero() {
    for (number, expected) in [
        ("-0", "零元整"),
        ("-0.00", "零元整"),
        ("-00.00", "零元整"),
        ("-0.05", "负伍分"),
        ("-0.50", "负伍角"),
        ("-1.05", "负壹元零伍分"),
        ("+12.05", "壹拾贰元零伍分"),
    ] {
        assert_eq!(money_upper(number).as_deref(), Some(expected), "{number}");
    }
}

#[test]
fn financial_digit_limits_stay_exact_for_signed_numbers() {
    assert_eq!(
        money_upper("-9999999999999999.99").as_deref(),
        Some("负玖仟玖佰玖拾玖万亿玖仟玖佰玖拾玖亿玖仟玖佰玖拾玖万玖仟玖佰玖拾玖元玖角玖分")
    );
    for number in ["10000000000000000", "-10000000000000000", "1.001", "-1.001", "--1", "+-1", "1..2"] {
        assert_eq!(money_upper(number), None, "{number}");
    }
    assert!(v_candidates("-1.001").iter().all(|(_, label)| label != "大写金额"));
    assert_eq!(v_candidates("-1.001")[1].0, "负一点零零一");
}

#[test]
fn existing_integer_candidates_keep_their_order() {
    assert_eq!(
        v_candidates("1234"),
        vec![
            ("1234".into(), "".into()),
            ("壹仟贰佰叁拾肆元整".into(), "大写金额".into()),
            ("一千二百三十四".into(), "中文数字".into()),
            ("1,234".into(), "千分位".into()),
        ]
    );
    assert_eq!(v_candidates("12").len(), 3);
    assert_eq!(v_candidates("0")[2].0, "零");
}

#[test]
fn numeric_mode_does_not_intercept_english_and_percent_stays_remainder() {
    for c in "veryxX".chars() {
        assert!(!v_accepts(c), "{c}");
    }
    for body in ["very", "vx", "x", "1x2", "1X2", "1+very"] {
        assert!(!is_expression(body), "{body}");
        assert!(v_candidates(body).is_empty(), "{body}");
    }
    assert_eq!(eval("3x4"), Some(12.0));
    assert_eq!(eval("7%3"), Some(1.0));
    assert_eq!(v_candidates("7%3")[0].0, "1");
    for expression in ["7%", "7%0", "1/0", "1+"] {
        assert!(v_candidates(expression).is_empty(), "{expression}");
    }
}
