import React from 'react';
import PropTypes from 'prop-types';
import axios from 'axios';
import SortableTable from 'react-sortable-table';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import TimeIntervalComponent from '../common/TimeIntervalComponent.jsx';
import TimestampFormatter from '../common/TimestampFormatter.jsx';
import ErrorViewComponent from '../common/ErrorViewComponent.jsx';
import BreadcrumbComponent from "../common/BreadcrumbComponent.jsx";

class ScanTargetsList extends React.Component {
    constructor(props){
        super(props);

        this.state = {
            loading: false,
            lastError: null,
            scanTargets: []
        };

        /*
        bucketName:String, enabled:Boolean,
         lastScanned:Option[ZonedDateTime],
         scanInterval:Long, scanInProgress:Boolean, lastError:Option[String])

         */
        this.columns = [{
                header: "Bucket",
                key: "bucketName",
                defaultSorting: "desc",
                headerProps: {className: "dashboardheader"}
            },
            {
                header: "Enabled",
                key: "enabled",
                headerProps: {className: "dashboardheader"},
                render: value=> value ? <span><FontAwesomeIcon icon="check-circle" className="inline-icon" style={{color:"green"}}/>yes</span> : <span><FontAwesomeIcon icon="times-circle" className="far inline-icon" style={{color: "darkred"}}/>no</span>
            },
            {
                header: "Last Scan",
                key: "lastScanned",
                headerProps: {className: "dashboardheader"},
                render: value=><TimestampFormatter relative={true} value={value}/>
            },
            {
                header: "Scan Interval",
                key: "scanInterval",
                headerProps: {className: "dashboardheader"},
                render: value=><TimeIntervalComponent editable={false} value={value}/>
            },
            {
                header: "Currently scanning",
                key: "scanInProgress",
                headerProps: {className: "dashboardheader"},
                render: value=> value ? "yes" : "no"
            },
            {
                header: "Last scan error",
                key: "lastError",
                headerProps: {className: "dashboardheader"},
                render: value=>value ? value : "-"
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

    }

    componentWillMount(){
        this.setState({loading: true}, ()=>axios.get("/api/scanTarget").then(response=>{
            this.setState({loading: false, lastError: null, scanTargets: response.data.entries});
        }).catch(err=>{
            console.error(err);
            this.setState({loading: false, lastError: err});
        }));
    }

    render(){
        if(this.state.error){
            return <ErrorViewComponent error={this.state.error}/>
        }
//
        return <div>
            <BreadcrumbComponent path={this.props.location.pathname}/>
            <SortableTable
            data={this.state.scanTargets}
            columns={this.columns}
            style={this.style}
            iconStyle={this.iconStyle}
            tableProps={ {className: "dashboardpanel"} }
        /></div>
    }
}

export default ScanTargetsList;