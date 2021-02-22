import React from "react";
import SizeInput from "../common/SizeInput";
import {UserProfileRow} from "../types";
import {makeStyles, Typography} from "@material-ui/core";
import {baseStyles} from "../BaseStyles";

interface RestoreLimitComponentProps {
    row: UserProfileRow;
    quotaChanged: (entry:UserProfileRow, fieldName: string, newValue: number)=>void;
}

const useStyles = makeStyles(Object.assign({
    restoreComponentList: {
        lineHeight: "40px",
        listStyle: "none"
    }
}, baseStyles));

const RestoreLimitComponent:React.FC<RestoreLimitComponentProps> = (props) => {
    const classes = useStyles();

    return <ul className={classes.restoreComponentList}>
        <li><Typography className="list-control-label">Single restore limit</Typography>
            <SizeInput sizeInBytes={props.row.perRestoreQuota ? (props.row.perRestoreQuota*1048576) : 0}
                       didUpdate={(newValue:number)=>props.quotaChanged(props.row,"PER_RESTORE_QUOTA", newValue)}
                       minimumMultiplier={1048576}
            />
            <Typography variant="caption">This is the amount of data that a user can request from Glacier without requiring administrator approval. Set to zero to always require admin approval.</Typography>

        </li>
        <li style={{display: "none"}}><p className="list-control-label">Rolling 30-day restore limit</p>
            <SizeInput sizeInBytes={props.row.rollingRestoreQuota ? (props.row.rollingRestoreQuota*1048576) : 0}
                       didUpdate={(newValue:number)=>props.quotaChanged(props.row,"ROLLING_QUOTA", newValue)}
                       minimumMultiplier={1048576}
            />
        </li>
        <li style={
            //{display: entry.isAdmin ? "list-item" : "none"}
            {display: "none"}
        }><Typography className="list-control-label">Admin's one-off authorisation limit:</Typography>
            <SizeInput sizeInBytes={props.row.adminAuthQuota ? (props.row.adminAuthQuota*1048576) : 0}
                       didUpdate={(newValue:number)=>props.quotaChanged(props.row,"ADMIN_APPROVAL_QUOTA", newValue)}
                       minimumMultiplier={1048576}
            />
        </li>
        <li style={
            //{display: entry.isAdmin ? "list-item" : "none"}
            {display: "none"}
        }><Typography className="list-control-label">Admin's rolling 30-day authorisation limit:</Typography>
            <SizeInput sizeInBytes={props.row.adminRollingAuthQuota ? (props.row.adminRollingAuthQuota*1048576) : 0}
                       didUpdate={(newValue:number)=>props.quotaChanged(props.row,"ADMIN_ROLLING_APPROVAL_QUOTA", newValue)}
                       minimumMultiplier={1048576}
            />
        </li>
    </ul>
}

export default RestoreLimitComponent;