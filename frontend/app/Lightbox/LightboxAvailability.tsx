import React, {useEffect, useState} from "react";
import moment, {Moment} from "moment";
import {makeStyles, Typography} from "@material-ui/core";
import TimestampFormatter from "../common/TimestampFormatter";
import {RestoreStatus} from "../types";

interface LightboxAvailabilityProps {
    maybeAvailableUntil?: string;
    restoreStatus: RestoreStatus;
}

const useStyles = makeStyles({
    runOnText: {
        display: "inline"
    },
});

const LightboxAvailability:React.FC<LightboxAvailabilityProps> = (props) => {
    const [expiryDate, setExpiryDate] = useState<Moment|undefined>();

    const classes = useStyles();

    useEffect(()=>{
        if(props.maybeAvailableUntil) {
            try {
                const parsedDate = moment(props.maybeAvailableUntil);
                setExpiryDate(parsedDate);
            } catch(err) {
                console.error("Could not parse timestring ", props.maybeAvailableUntil, ": ", err);
            }
        } else {
            setExpiryDate(undefined);
        }
    }, [props.maybeAvailableUntil]);

    if(expiryDate) {
        if(expiryDate.isBefore(moment())) {
            return <Typography className={classes.runOnText}>Expired {expiryDate.fromNow(false)}</Typography>
        } else {
            return <Typography className={classes.runOnText}>Available for {expiryDate.fromNow(false)}</Typography>
        }
    } else {
        switch(props.restoreStatus) {
            case "RS_UNNEEDED":
                return <Typography className={classes.runOnText}>Available indefinitely</Typography>
            case "RS_ERROR":
                return <Typography className={classes.runOnText}>Not available</Typography>
            case "RS_EXPIRED":
                return <Typography className={classes.runOnText}>Returned to archive</Typography>
            default:
                return <></>
        }

    }
}

export default LightboxAvailability;
