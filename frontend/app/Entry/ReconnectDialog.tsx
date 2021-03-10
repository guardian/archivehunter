import React, {useEffect, useState} from "react";
import {ProxyLocation} from "../types";
import {
    Button, CircularProgress,
    Dialog,
    DialogContent,
    DialogTitle,
    Divider,
    Grid,
    makeStyles,
    Radio,
    Typography
} from "@material-ui/core";
import axios from "axios";
import {formatError} from "../common/ErrorViewComponent";

interface ReconnectDialogProps {
    potentialProxies: ProxyLocation[];
    itemName: string;
    itemId: string;
    onDialogClose: (saved:boolean)=>void;
    onError?: (err:string)=>void;
}

const useStyles = makeStyles({
    mainlist: {
        listStyle: "none",
        padding: 0
    },
    selectorText: {
        paddingTop: "0.5em"
    },
    spinner: {width: "26px", height: "26px", display:"inline", marginRight: "0.4em"}
});

const ReconnectDialog:React.FC<ReconnectDialogProps> = (props)=>{
    const [selectedProxyId, setSelectedProxyId] = useState<string>(props.potentialProxies.length>0 ? props.potentialProxies[0].proxyId : "");
    const [actionMessage, setActionMessage] = useState<string|undefined>(undefined);
    const [inProgress, setInProgress] = useState(false);

    const classes = useStyles();

    const saveSelection = async ()=> {
        try {
            setInProgress(true);
            setActionMessage("Saving...");
            await axios.put(`/api/proxy/${encodeURIComponent(props.itemId)}/associate?id=${encodeURIComponent(selectedProxyId)}`);
            setInProgress(false);
            setActionMessage("Proxy associated");
            props.onDialogClose(true);
        } catch(err) {
            setInProgress(false);
            setActionMessage(formatError(err, true));
            if(props.onError) props.onError(formatError(err, false));
        }
    }

    return <Dialog open={true} onClose={props.onDialogClose} aria-labelledby="reconnect-title">
        <DialogTitle id="reconnect-title">
            Reconnect {props.itemName}
        </DialogTitle>
        <DialogContent dividers>
            <ul className={classes.mainlist}>
                {
                    props.potentialProxies.map((entry,idx)=><li className={classes.mainlist} key={idx}>
                        <Grid container direction="row" spacing={3}>
                            <Grid item>
                                <Radio
                                    checked={selectedProxyId===entry.proxyId}
                                    onChange={()=>setSelectedProxyId(entry.proxyId)}
                                />
                            </Grid>
                            <Grid item>
                                <Typography className={classes.selectorText}>{entry.proxyType}</Typography>
                            </Grid>
                            <Grid item>
                                <Typography className={classes.selectorText}>{entry.bucketName}</Typography>
                            </Grid>
                            <Grid item>
                                <Typography className={classes.selectorText}>{entry.bucketPath}</Typography>
                            </Grid>
                            <Grid item>
                                <Typography className={classes.selectorText}>{entry.region}</Typography>
                            </Grid>
                        </Grid>
                    </li>)
                }
            </ul>
            <Divider/>
            <Grid container direction="row" justify="space-between">
                <Grid item>
                    <Button variant="contained" onClick={()=>props.onDialogClose(false)} disabled={inProgress}>Cancel</Button>
                </Grid>
                <Grid item>
                        <>
                            {inProgress ? <CircularProgress className={classes.spinner}/> : null}
                            {actionMessage ? <Typography>{actionMessage}</Typography>: null}
                        </>
                </Grid>
                <Grid item>
                    <Button variant="contained" onClick={saveSelection} disabled={inProgress}>Link selected</Button>
                </Grid>
            </Grid>
        </DialogContent>
    </Dialog>
}

export default ReconnectDialog;


