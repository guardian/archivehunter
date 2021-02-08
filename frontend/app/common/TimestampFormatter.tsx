import React from 'react';
import moment from 'moment';
import {Typography} from "@material-ui/core";

interface TimestampFormatterProps {
    relative: boolean;
    value: string;
    formatString?: string;
    className?: string;
}

const TimestampFormatter:React.FC<TimestampFormatterProps> = (props) => {
    const formatToUse = props.formatString ? props.formatString : "";
    const m = moment(props.value);

    const out = props.relative ? m.fromNow(false) : m.format(formatToUse);
    return <Typography className={props.className}>{out}</Typography>
}

export default TimestampFormatter;