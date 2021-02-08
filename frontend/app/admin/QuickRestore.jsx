import React from "react";
import axios from "axios";
import BytesFormatter from "../common/BytesFormatter.jsx";
import RefreshButton from "../common/RefreshButton";
import AdminContainer from "./AdminContainer";
import {createStyles, withStyles, Snackbar, TextField, Paper, Button, Grid, Typography} from "@material-ui/core";
import {formatError} from "../common/ErrorViewComponent";
import MuiAlert from "@material-ui/lab/Alert";
import {Check, Search, Warning, WarningRounded, WbIncandescent} from "@material-ui/icons";

const styles = createStyles((theme)=>({
    wide: {
        width: "100%"
    },
    errorText: {
        color: theme.palette.error.dark,
        fontWeight: "bold"
    },
    successText: {
        color: theme.palette.success.dark,
        fontWeight: "bold",
    },
    formList: {
     listStyle: "none"
    },
    formItem: {
        marginBottom: "1em"
    },
    area: {
        paddingBottom: "2em",
        paddingRight: "2em",
        marginBottom: "3em"
    },
    successIcon: {
        color: theme.palette.success.dark,
        width: "200px",
        height: "200px"
    },
    warningIcon: {
        color: theme.palette.warning.dark,
        width: "200px",
        height: "200px"
    },
    bigIcon: {
        width: "200px",
        height: "200px"
    }
}));

class QuickRestoreComponent extends React.Component {
    constructor(props) {
        super(props);

        this.state = {
            isLoading: false,
            isLightboxing: false,
            showingAlert: false,
            quotaExceeded: false,
            quotaRequired: 0,
            quotaLevel: 0,
            lastError: null,
            requestedPath: "",
            collectionName: "",
            fileCount: 0,
            totalSize: 0,
            success: false
        }
        this.doPathCheck = this.doPathCheck.bind(this);
        this.doRestore = this.doRestore.bind(this);
    }

    makeSearchJson() {
        return JSON.stringify({
            hideDotFiles: false,
            collection: this.state.collectionName,
            path: this.state.requestedPath
        })
    }

    static getDerivedStateFromError(error) {
        console.error(error);
        return {
            isLoading: false,
            isLightboxing: false,
            quotaExceeded: false,
            quotaRequired: 0,
            quotaLevel: 0,
            requestedPath: "",
            collectionName: "",
            fileCount: 0,
            totalSize: 0,
            showingAlert: true,
            lastError: "A frontend error occurred, probably a bug. See the console for details."
        }
    }

    doPathCheck() {
        console.log("doPathCheck");

        const pathToSearch = this.state.requestedPath.endsWith("/") ? this.state.requestedPath.slice(0, this.state.requestedPath.length - 1) : this.state.requestedPath;

        const urlsuffix = pathToSearch ? "?prefix=" + encodeURIComponent(pathToSearch) : "";
        const url = "/api/browse/" + this.state.collectionName + "/summary" + urlsuffix;

        this.setState({isLoading:true, lastError: null},
            ()=>axios.put(url, this.makeSearchJson(), {headers: {"Content-Type": "application/json"}}).then(response=>{
                this.setState({
                    isLoading:false,
                    lastError:null,
                    fileCount: response.data.totalHits,
                    totalSize: response.data.totalSize,
                })
            }).catch(err=>{
                console.error("Could not refresh path summary data: ", err);
                this.setState({loading: false,lastError: formatError(err, false), showingAlert: true})
            }))
    }

    doRestore() {
        console.log("doLightbox");
        this.setState({isLightboxing: true, lastError:null},
            ()=>axios.put("/api/lightbox/my/addFromSearch", this.makeSearchJson(),{headers:{"Content-Type":"application/json"}}).then(response=>{
                console.log(response.data);
                this.setState({isLightboxing:false, success: true});
            }).catch(err=>{
                if(err.response && err.response.status===413){
                    console.log(err.response.data);
                    this.setState({
                        isLightboxing: false,
                        quotaExceeded: true,
                        quotaRequired: err.response.data.requiredQuota,
                        quotaLevel: err.response.data.actualQuota
                    })
                } else {
                    this.setState({loading: false, lastError: formatError(err, false), showingAlert: true});
                }
            })
        )
    }

    closeAlert() {
        this.setState({showingAlert: false});
    }

    render() {
        return (
            <AdminContainer {...this.props}>
                <Snackbar open={this.state.showingAlert} onClose={this.closeAlert} autoHideDuration={8000}>
                    <MuiAlert severity="error" onClose={this.closeAlert}>{this.state.lastError}</MuiAlert>
                </Snackbar>
                <Paper elevation={3} className={this.props.classes.area}>
                <ul className={this.props.classes.formList}>
                    <li className={this.props.classes.formItem}>
                        {/*<label htmlFor="collection-input">Collection name to restore from:</label>*/}
                        <TextField
                               label="Collection name to restore from"
                               className={this.props.classes.wide}
                               id="collection-input"
                               value={this.state.collectionName}
                               onChange={(evt)=>this.setState({collectionName: evt.target.value})}
                               />
                    </li>
                    <li className={this.props.classes.formItem}>
                        {/*<label htmlFor="path-input">Path to restore:</label>*/}
                        <TextField
                            label="Path to restore"
                            className={this.props.classes.wide}
                               id="path-input"
                               value={this.state.requestedPath}
                               onChange={(evt)=>this.setState({requestedPath: evt.target.value})}
                               />
                    </li>
                    <li className={this.props.classes.formItem}>
                        <>
                            <Button variant="outlined"
                                    startIcon={<Search/>}
                                    onClick={this.doPathCheck}
                                    disabled={this.state.isLoading}
                            >Check path</Button>
                            {this.state.isLoading ? <RefreshButton isRunning={true} clickedCb={()=>{}}/> : null}
                        </>
                    </li>
                </ul>
                </Paper>
                <Paper elevation={3} className={this.props.classes.area}>
                    <Grid container alignItems="center">
                        <Grid item>
                            {
                                this.state.fileCount===0 && !this.state.quotaExceeded ? <Search className={this.props.classes.bigIcon}/> : null
                            }
                            {
                                this.state.fileCount>0 && !this.state.quotaExceeded ? <Check className={this.props.classes.successIcon}/> : null
                            }
                            {
                                this.state.quotaExceeded ? <WarningRounded className={this.props.classes.warningIcon}/> : null
                            }
                        </Grid>

                        <Grid item>
                            <ul className={this.props.classes.formList}>
                                <li className={this.props.classes.formItem}>
                                    <Typography>There are {this.state.fileCount} files in the path selected totalling <BytesFormatter value={this.state.totalSize}/></Typography>
                                </li>
                                <li>
                                    <>
                                        <Button
                                            startIcon={<WbIncandescent/>}
                                            variant="contained"
                                            disabled={this.state.isLoading || this.state.fileCount===0 || this.state.success}
                                            onClick={this.doRestore}
                                        >Add to Lightbox</Button>
                                        {this.state.isLightboxing ? <RefreshButton isRunning={true} clickedCb={()=>{}}/> : null}
                                        {this.state.quotaExceeded ?
                                            <p className={this.props.classes.errorText}>This restore would exceed your quota of <BytesFormatter value={this.state.quotaLevel}/>.
                                                You need <BytesFormatter value={this.state.quotaRequired}/></p> : null
                                        }
                                        {
                                            this.state.success ?
                                                <Typography className={this.props.classes.successText}>Now go to "My Lightbox" to see the items</Typography> : null
                                        }
                                    </>
                                </li>
                            </ul>
                        </Grid>
                    </Grid>
                </Paper>
            </AdminContainer>
        )
    }
}

export default withStyles(styles)(QuickRestoreComponent);