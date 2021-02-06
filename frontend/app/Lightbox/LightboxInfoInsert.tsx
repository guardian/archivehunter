import React from 'react';
import TimestampFormatter from "../common/TimestampFormatter";
import RestoreStatusComponent from "./RestoreStatusComponent.jsx";
import {FontAwesomeIcon} from "@fortawesome/react-fontawesome";
import {LightboxEntry} from "../types";
import {makeStyles} from "@material-ui/core";
import {baseStyles} from "../BaseStyles";
import {IconProp} from "@fortawesome/fontawesome-svg-core";

interface LightboxInfoInsertProps {
    entry: LightboxEntry;
    extraInfo?: string;
    iconName?: IconProp;
}

const useStyles = makeStyles(Object.assign({}, baseStyles));

const LightboxInfoInsert:React.FC<LightboxInfoInsertProps> = (props) => {
    const classes = useStyles();

    if(!props.entry) return <div/>;

    return <div className={classes.centered}>
        <span style={{display: "block"}}>
            Added to lightbox <TimestampFormatter relative={true} value={props.entry.addedAt}/>
        </span>
        <p className={classes.centered}>Archive availability</p>
        <RestoreStatusComponent
            status={props.entry.restoreStatus}
            startTime={props.entry.restoreStarted}
            completed={props.entry.restoreCompleted}
            expires={props.entry.availableUntil}
            hidden={props.entry.restoreStatus==="RS_UNNEEDED"}/>
        {
            props.extraInfo ? <p className={classes.centered}>
                {props.iconName ? <FontAwesomeIcon icon={props.iconName} size="2x" style={{color:"yellow"}}/> : null }
                {props.extraInfo}
            </p> : ""
        }
    </div>
}

export default LightboxInfoInsert;