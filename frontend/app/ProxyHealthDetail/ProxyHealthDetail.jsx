import React from 'react';
import axios from 'axios';
import BreadcrumbComponent from "../common/BreadcrumbComponent.jsx";
import TimestampFormatter from "../common/TimestampFormatter.jsx";
import TimestampDiffComponent from "../common/TimestampDiffComponent.jsx";
import ErrorViewComponent from "../common/ErrorViewComponent.jsx";
import ByCollectionChart from "./ByCollectionChart.jsx";
import GeneralOverviewChart from "./GeneralOverviewChart.jsx";
import InfoTable from "./InfoTable.jsx";

class ProxyHealthDetail extends React.Component {
    constructor(props) {
        super(props);

        this.state = {
            loading: false,
            lastError: null,
            collectionSummary: [],
            mostRecentRun: null,
            previousRuns: [],
            tableData: [],
            selectedCollection: null,
            tableStart: 0,
            tablePageSize: 100,
            tableMaxSize: 100
        };

        this.collectionSelectedCb = this.collectionSelectedCb.bind(this);
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
                        lastError: err
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
            this.setState({loading: false, lastError: err});
        }))
    }

    /**
     * blank out current table data and load from the start
     */
    reloadTableData() {
        this.setState({tableData:[], tableStart: 0}, ()=>this.loadMoreTableData());
    }

    componentWillMount() {
        this.loadData().then(()=>this.reloadTableData());
    }

    render() {
        return <div>
            <BreadcrumbComponent path={this.props.location.pathname}/>

            <div className="chart-error-container">
                <ErrorViewComponent error={this.state.lastError}/>
            </div>

            <div className="chart-container">
                <h3>Proxy Health</h3>
                {this.state.mostRecentRun ? <div className="info-box">Last check:
                        <ul>
                            <li>was at <TimestampFormatter relative={false} value={this.state.mostRecentRun.scanStart} formatString="hh:mm:ss a [on] MMMM Do YYYY"/>
                            </li>
                            <li>completed <TimestampDiffComponent startTime={this.state.mostRecentRun.scanStart}
                                                           endTime={this.state.mostRecentRun.scanFinish}/></li>
                        </ul>
                </div> : <div className="info-box"><p>Loading...</p></div>
                }
            </div>
            <div className="chart-container">
                <GeneralOverviewChart recentCountData={this.state.mostRecentRun}/>
            </div>

            <div className="chart-container" style={{float:"right"}}>
                <ByCollectionChart facetData={this.state.collectionSummary} dataSeriesClicked={this.collectionSelectedCb}/>
            </div>

            <div style={{display:"block", float:"left", width: "100vw"}}>
                <InfoTable tableData={this.state.tableData}/>
            </div>
        </div>
    }
}

export default ProxyHealthDetail;
