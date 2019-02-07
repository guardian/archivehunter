import React from 'react';
import axios from 'axios';
import {Redirect} from 'react-router-dom';
import ErrorViewComponent from "../common/ErrorViewComponent.jsx";
import BreadcrumbComponent from "../common/BreadcrumbComponent.jsx";
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import LoadingThrobber from "../common/LoadingThrobber.jsx";
import SortableTable from 'react-sortable-table';
import {handle419} from "../common/Handle419.jsx";

class ProxyFrameworkList extends React.Component {
    constructor(props){
        super(props);

        this.state = {
           currentDeployments: [],
           loading: false,
           lastError: null,
            goToNew: false
        };

        this.columns = [
            {
                header: "Region",
                key: "region",
                headerProps: {className: "dashboardheader"}

            },
            {
                header: "Input Topic",
                key: "inputTopicArn",
                headerProps: {className: "dashboardheader"}
            },
            {
                header: "Reply Topic",
                key: "outputTopicArn",
                headerProps: {className: "dashboardheader"}
            },
            {
                header: "Management Role",
                key: "roleArn",
                headerProps: {className: "dashboardheader"}
            },
            {
                header: "Subscription",
                key: "subscriptionId",
                headerProps: {className: "dashboardheader"}
            },
            {
                header: "Actions",
                key: "region",
                headerProps: {className: "dashboardheader"},
                render: value=><span>
                    <FontAwesomeIcon icon="trash-alt" onClick={()=>this.callDelete(value)}/>
                </span>
            }
        ];
        this.style = {
            backgroundColor: '#eee',
            border: '1px solid black',
            borderCollapse: 'collapse'
        };

        this.iconStyle = {
            color: '#aaa',
            paddingLeft: '5px',
            paddingRight: '5px'
        };

        this.callDelete = this.callDelete.bind(this);
    }

    loadData(){
        this.setState({loading: true, lastError:null}, ()=>axios.get("/api/proxyFramework/deployments").then(response=>{
            this.setState({loading: false, lastError:null, currentDeployments: response.data.entries})
        }).catch(err=>{
            console.error(err);
            handle419(err).then(didRefresh=>{
                if(didRefresh){
                    this.loadData();
                } else {
                    this.setState({loading: false, lastError: err});
                }
            });
        }));
    }

    componentWillMount(){
        this.loadData();
    }

    callDelete(region){
        this.setState({loading:true, lastError:null}, ()=>axios.delete("/api/proxyFramework/deployments/" + region).then(response=>{
            this.loadData();
        }).catch(err=>{
            console.error(err);
            handle419(err).then(didRefresh=>{
                if(didRefresh){
                    this.callDelete(region);
                } else {
                    this.setState({loading: false, lastError: err});
                }
            });
        }))
    }
    render(){
        if(this.state.lastError) return <ErrorViewComponent error={this.state.lastError}/>;
        if(this.state.goToNew) return <Redirect to="/admin/proxyFramework/new"/>;
        return <div>
            <BreadcrumbComponent path={this.props.location.pathname}/>
            <LoadingThrobber show={this.state.loading} caption="Loading data..." small={true}/>
            <div style={{height: "40px", width: "100%", display: "block", overflow: "hidden"}}>
                <button onClick={()=>this.setState({goToNew: true})} style={{float: "right"}}>New...</button>
            </div>
            {
                this.state.currentDeployments.length===0 ? <p className="centered">No connected deployments</p> : <SortableTable
                    data={this.state.currentDeployments}
                    columns={this.columns}
                    style={this.style}
                    iconStyle={this.iconStyle}
                    tableProps={{className: "dashboardpanel"}}/>
            }
        </div>
    }
}

export default ProxyFrameworkList;