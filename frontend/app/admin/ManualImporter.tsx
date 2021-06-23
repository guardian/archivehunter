import React, {useEffect, useState} from "react";
import {RouteComponentProps} from "react-router";
import AdminContainer from "./AdminContainer";
import {Helmet} from "react-helmet";
import {
    Button,
    FormControlLabel,
    Grid,
    Input,
    makeStyles,
    Paper,
    Snackbar,
    TextField,
    Typography
} from "@material-ui/core";
import MuiAlert from "@material-ui/lab/Alert";
import {formatError} from "../common/ErrorViewComponent";
import axios from "axios";
import {numberComparer} from "@material-ui/data-grid";

interface ManualImporterProps {

}

const useStyles = makeStyles({
    inputBox: {
        width: "100%"
    }
});

const ManualImporter:React.FC<RouteComponentProps<ManualImporterProps>> = (props) => {
    const [bucketName, setBucketName] = useState("");
    const [pathList, setPathList] = useState("");
    const [pathsCount, setPathsCount] = useState(0);
    const [controlsEnabled, setControlsEnabled] = useState(true);
    const [progressText, setProgressText] = useState("");
    const [notExistCount, setNotExistCount] = useState(0);
    const [importedCount, setImportedCount] = useState(0);
    const [completed, setCompleted] = useState(false);

    const classes = useStyles();

    useEffect(()=>{
        const newPathCount = pathList
            .split("\n")
            .filter(line=>line.length>0)
            .length;
        if(newPathCount!=pathsCount) setPathsCount(newPathCount);
    }, [pathList]);

    const asyncDelay = (amount:number)=>new Promise<void>((resolve, reject)=>window.setTimeout(()=>resolve(), amount));

    const singleImportRequest = async (filePath:string) => {
        const req = {
            collectionName: bucketName,
            itemPath: filePath
        };

        const response = await axios.post(`/api/import`,req,{validateStatus: ()=>true});
        switch(response.status) {
            case 200:
                setProgressText(`Import of ${filePath} successful`);
                setImportedCount((prev)=>prev+1);
                break;
            case 409:
                setProgressText(`Item ${filePath} was already imported`);
                break;
            case 400:
                const errorContent = response.data as {detail: string};
                throw errorContent.detail;
            case 404:
                setProgressText(`Item ${filePath} does not exist`);
                setNotExistCount((prev)=>prev+1);
                await asyncDelay(1000);
                break;
            case 419:
                throw "Session expired";
            default:
                console.error("Import gave server error ", response.status, response.statusText, response.data);
                throw "Server error";
        }
    }

    const doImport = async ()=>{
        if(pathsCount==0) {
            alert("Nothing to import! Try pasting some paths in first")
        }

        setControlsEnabled(false);
        const toImport = pathList
            .split("\n")
            .filter(line=>line.length>0);

        let i=0;
        for(let toImportCount=toImport.length;toImportCount>0;toImportCount--){
            setProgressText(`${toImportCount} items to import`);
            try {
                await singleImportRequest(toImport[i]);
            } catch(err) {
                setProgressText(`Import failed: ${err}`);
                setControlsEnabled(true);
                return
            }
            i++
        }
        setControlsEnabled(true);
        setCompleted(true);
    }

    return (
        <AdminContainer {...props}>
            <Helmet>
                <title>Manual import - ArchiveHunter</title>
            </Helmet>

            <Paper elevation={3} style={{padding: "1em"}}>
                <Typography variant="h4">Manual (quick) import</Typography>
                <Typography>
                    You can tell ArchiveHunter to import specific files that already exist in a known bucket ("collection")
                    but that have not got into the index for some reason.
                </Typography>
                <Typography>
                    Simply paste the full paths into the text box below, and fill in the name of the bucket that they
                    belong to.  When you hit the "import" button, the system will try to import them one at a time
                    and will update the status here.
                </Typography>

                <Grid container direction="column" style={{marginTop: "2em"}} spacing={1}>
                    <Grid item container direction="row" spacing={3} justify="space-between">
                        <Grid item>
                            <Button variant="contained" onClick={doImport} disabled={!controlsEnabled && pathsCount>0}>Import {pathsCount} items</Button>
                        </Grid>
                        <Grid item>
                            <Typography>{progressText}</Typography>
                        </Grid>
                        <Grid item>
                            {
                                completed ?
                                    <Typography>Import completed.  {importedCount} / {pathsCount} imported and {notExistCount} / {pathsCount} did not exist.</Typography> :
                                    undefined
                            }
                        </Grid>
                    </Grid>
                    <Grid item>
                        <TextField  onChange={(evt)=>setBucketName(evt.target.value)}
                                    value={bucketName}
                                    label="Bucket name"
                                    disabled={!controlsEnabled}
                                    className={classes.inputBox}
                        />
                    </Grid>
                    <Grid item>
                        <TextField multiline
                                   onChange={(evt)=>setPathList(evt.target.value)}
                                   value={pathList}
                                   label="Paths"
                                   disabled={!controlsEnabled}
                                   rows={30}
                                   className={classes.inputBox}
                        />
                    </Grid>
                </Grid>
            </Paper>
        </AdminContainer>
    )
}

export default ManualImporter;