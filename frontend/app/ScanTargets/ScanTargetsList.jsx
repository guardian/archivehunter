import React from 'react';
import PropTypes from 'prop-types';
import axios from 'axios';
import SortableTable from 'react-sortable-table';

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
                headerProps: "dashboardheader"
            },
            {
                header: "Enabled",
                key: "enabled",
                headerProps: "dashboardheader"
            },
            {
                header: "Last Scan",
                key: "lastScanned",
                headerProps: "dashboardheader",
                render: value=><TimestampFormatter relative={true} value={value}/>
            },
            {
                header: "Scan Interval",
                key: "scanInterval",
                headerProps: "dashboardheader",
                render: value=><TimeIntervalComponent editable={false}/>
            },
            {
                header: "Currently scanning",
                key: "scanInProgress",
                headerProps: "dashboardheader"
            },
            {
                header: "Last scan error",
                key: "lastError",
                headerProps: "dashboardheader"
            }
        ]
    }

    componentWillMount(){
        this.setState({loading: true}, ()=>axios.get("/api/scanTarget").then(response=>{
            this.setState({loading: false, lastError: null, scanTargets: response.entries});
        }).catch(err=>{
            console.error(err);
            this.setState({loading: false, lastError: err});
        }));
    }

    render(){
        return <SortableTable data={this.state.data}
            columns={this.columns}
            style={this.style}
            iconStyle={this.iconStyle}
            tableProps={ {className: "dashboardpanel"} }
        />
    }
}

export default ScanTargetsList;