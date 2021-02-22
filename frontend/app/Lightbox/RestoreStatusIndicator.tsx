import React from "react";
import {LightboxEntry} from "../types";
import {Tooltip} from "@material-ui/core";
import {AcUnit, Check} from "@material-ui/icons";

interface RestoreStatusIndicatorProps {
    entry: LightboxEntry;
    className?: string;
}

const RestoreStatusIndicator:React.FC<RestoreStatusIndicatorProps> = (props) => {
    const friendlyDescription = () => {
        switch(props.entry.restoreStatus) {
            case "RS_PENDING":
                return "Restore not started";
            case "RS_ERROR":
                return "Restore failed";
            case "RS_ALREADY":
            case "RS_SUCCESS":
                return "Available";
            case "RS_UNNEEDED":
                return "Not in deep-freeze";
            case "RS_UNDERWAY":
                return "Restore in progress";
        }
    }

    const statusIcon = () => {
        switch(props.entry.restoreStatus) {
            case "RS_PENDING":
            case "RS_ERROR":
                return <AcUnit className={props.className} style={{color: "lightblue"}}/>;
            case "RS_ALREADY":
            case "RS_SUCCESS":
            case "RS_UNNEEDED":
                return <Check className={props.className} style={{color: "green"}}/>;
            case "RS_UNDERWAY":
                return <AcUnit className={props.className} style={{color: "orange"}}/>;
        }
    }

    return <Tooltip title={friendlyDescription()}>
        {
            statusIcon()
        }
    </Tooltip>
}

export default RestoreStatusIndicator;