import React from 'react';
import axios from 'axios';
import TimestampFormatter from "../common/TimestampFormatter.jsx";
import TimestampDiffComponent from "../common/TimestampDiffComponent.jsx";
import ErrorViewComponent from "../common/ErrorViewComponent.jsx";
import ByCollectionChart from "./ByCollectionChart.jsx";
import GeneralOverviewChart from "./GeneralOverviewChart.jsx";
import InfoTable from "./InfoTable";
import LoadingThrobber from "../common/LoadingThrobber.jsx";
import AdminContainer from "../admin/AdminContainer";
import {
    Button,
    CircularProgress,
    Divider,
    Grid,
    Paper,
    Snackbar,
    Tooltip,
    Typography,
    withStyles
} from "@material-ui/core";
import MuiAlert from "@material-ui/lab/Alert";
import {proxyHealthStyles} from "./ProxyHealthStyles";

class ProxyHealthDetail extends React.Component {
    constructor(props) {
        super(props);

        this.state = {
            loading: false,
            showingAlert: false,
            lastError: null,
            collectionSummary: [],
            mostRecentRun: null,
            previousRuns: [],
            tableData: [],
            selectedCollection: null,
            tableStart: 0,
            tablePageSize: 100,
            tableMaxSize: 2500,
            triggerInProgress: false
        };

        this.closeAlert = this.closeAlert.bind(this);
        this.collectionSelectedCb = this.collectionSelectedCb.bind(this);
        this.triggerSelectedCollectionFix = this.triggerSelectedCollectionFix.bind(this);
    }

    collectionSelectedCb(selected) {
        console.log("selected collection " + selected);
        this.setState({selectedCollection: selected}, ()=>this.reloadTableData());
    }

    loadData() {
        return new Promise((resolve, reject)=> {
            const loadingFuture = [
                axios.get("/api/proxyhealth/mostrecent"),
                axios.get("/api/proxyhealth/problemitems/collectionlist")
            ];

            this.setState({loading: true, lastError: null}, () => {
                axios.all(loadingFuture).then(responseList => {
                    this.setState({
                        loading: false,
                        lastError: null,
                        collectionSummary: responseList[1].data.entries,
                        mostRecentRun: responseList[0].data.entry
                    }, ()=>resolve())
                }).catch(err => {
                    console.error(err);
                    this.setState({
                        loading: false,
                        lastError: err,
                        showingAlert: true
                    }, ()=>reject(err))
                })
            })
        });
    }

    static filePathSplitter = /^(.*)\/([^\/]+)$/;

    /**
     * splits a filepath into filename/pathname components
     * @param filePath
     */
    splitPath(filePath){
        const parts = ProxyHealthDetail.filePathSplitter.exec(filePath);

        if(parts && parts.length>1){
            return [parts[1], parts[2]];
        } else {
            return [filePath, ""];
        }
    }

    convertVerifyResultFor(resultList, requiredType) {
        const matches = resultList.filter(entry=>entry.proxyType===requiredType);

        if(matches.length>0){
            return {
                wantProxy: matches[0].wantProxy,
                haveProxy: matches[0].haveProxy,
                known: true
            }
        } else {
            return {
                known: false,
                wantProxy: false,
                haveProxy: false
            }
        }
    }
    /**
     * takes a problem item entry from the server and converts it to a form that the table wants
     */
    convertToTableData(incomingData){
        return incomingData.map(entry=>{
            const pathParts = this.splitPath(entry.filePath);
            return {
                fileId: entry.fileId,
                collection: entry.collection,
                esRecordSays: entry.esRecordSays,
                filePath: pathParts[0],
                fileName: pathParts[1],
                thumbnailResult: this.convertVerifyResultFor(entry.verifyResults, "THUMBNAIL"),
                videoResult: this.convertVerifyResultFor(entry.verifyResults, "VIDEO"),
                audioResult: this.convertVerifyResultFor(entry.verifyResults, "AUDIO")
            }
        })
    }

    /**
     * get another page of data from the server and put it into the table. "Recurses" until there are state.tableMaxSize rows available.
     */
    loadMoreTableData() {
        const uri = "/api/proxyhealth/problemitems?size=" + this.state.tablePageSize + "&start=" + this.state.tableStart;
        const finalUri = this.state.selectedCollection ? uri + "&collection=" + this.state.selectedCollection : uri;

        this.setState({loading: true, lastError:null}, ()=>axios.get(finalUri).then(response=>{
            this.setState({
                loading: false,
                lastError: null,
                tableData: this.state.tableData.concat(this.convertToTableData(response.data.entries))
            },()=>{
                if(this.state.tableData.length<this.state.tableMaxSize) this.loadMoreTableData();
            });

        }).catch(err=>{
            console.error(err);
            this.setState({loading: false, lastError: err, showingAlert: true});
        }))
    }

    /**
     * blank out current table data and load from the start
     */
    reloadTableData() {
        this.setState({tableData:[], tableStart: 0}, ()=>this.loadMoreTableData());
    }

    componentDidMount() {
        this.loadData().then(()=>this.reloadTableData());
    }

    triggerSelectedCollectionFix(){
        this.setState({triggerInProgress: true}, ()=>axios.post("/api/proxyhealth/triggerproblems/" + this.state.selectedCollection)
            .then(response=>{
                this.setState({triggerInProgress: false})
            }).catch(err=>{
                console.error(err);
                this.setState({triggerInProgress: false, lastError: err, showingAlert: true});
            }))
    }

    closeAlert() {
        this.setState({showingAlert: false})
    }
    render() {
        return <AdminContainer {...this.props}>
            <Snackbar open={this.state.showingAlert} onClose={this.closeAlert} autoHideDuration={8000}>
                {
                    this.state.lastError ? <MuiAlert severity="error" onClose={this.closeAlert}>{ErrorViewComponent.formatError(this.state.lastError)}</MuiAlert> : null
                }
            </Snackbar>

            <Grid container className={this.props.classes.chartsBar} spacing={3} justify="space-between">
                <Grid item className={this.props.classes.chartContainer}>
                    <Typography variant="h5">Proxy Health</Typography>
                    {this.state.mostRecentRun ? <Paper elevation={0}>
                        <Typography>Last check:</Typography>
                        <ul>
                            <li>
                                <Typography>
                                    was at <TimestampFormatter relative={false}
                                                               value={this.state.mostRecentRun.scanStart}
                                                               formatString="hh:mm:ss a [on] MMMM Do YYYY"/>
                                </Typography>
                            </li>
                            <li>
                                <Typography>
                                    completed <TimestampDiffComponent startTime={this.state.mostRecentRun.scanStart}
                                                                      endTime={this.state.mostRecentRun.scanFinish}/>
                                </Typography>
                            </li>
                        </ul>
                        {
                            this.state.selectedCollection ? <Tooltip
                                title={"Re-run proxies for " + this.state.selectedCollection ? this.state.selectedCollection : "(none)"}>
                                <Button onClick={this.triggerSelectedCollectionFix}
                                        disabled={this.state.triggerInProgress}>Re-create</Button>
                            </Tooltip> : null
                        }
                    </Paper> : <div><CircularProgress/><Typography>Loading...</Typography></div>
                    }
                </Grid>

                <Grid item className={this.props.classes.chartContainer}>
                    <GeneralOverviewChart recentCountData={this.state.mostRecentRun}/>
                </Grid>

                <Grid item className={this.props.classes.chartContainer} >
                    <ByCollectionChart facetData={this.state.collectionSummary} dataSeriesClicked={this.collectionSelectedCb}/>
                </Grid>
            </Grid>

            <Divider className={this.props.classes.chartDivider}/>
            <InfoTable tableData={this.state.tableData}/>
        </AdminContainer>
    }
}

export default withStyles(proxyHealthStyles)(ProxyHealthDetail);
