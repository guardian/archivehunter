import React from 'react';
import moment from 'moment';
import {makeStyles, Typography} from "@material-ui/core";

interface TimestampFormatterProps {
    relative: boolean;
    value: string;
    formatString?: string;
}

const TimestampFormatter:React.FC<TimestampFormatterProps> = (props) => {
    const formatToUse = props.formatString ? props.formatString : "";
    const m = moment(props.value);

    const out = props.relative ? m.fromNow(false) : m.format(formatToUse);
    return <Typography>{out}</Typography>
}

export default TimestampFormatter;